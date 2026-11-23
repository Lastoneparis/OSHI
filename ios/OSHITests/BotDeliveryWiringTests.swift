//
//  BotDeliveryWiringTests.swift
//  OSHITests
//
//  __BOT_RUNTIME_OWNS_DELIVERY_2026_08_16__ — la preuve que l'étape 2 MARCHE.
//
//  `BotTriggerGuardTests` proves the RULE (what may fire a trigger).
//  `BotManifestTests` proves the DERIVATION. Neither proves the thing step 2
//  actually claims: that a bot now answers a message it did NOT send, with no
//  view anywhere on screen.
//
//  So this drives the real objects, in the running app on the simulator:
//
//      MessageGroupManager.shared.receiveGroupMessage(<message entrant>)
//          → BotManager.shared.processMessage           (le branchement neuf)
//          → BotManager.shared.emit                     (la sortie unique)
//          → MessageGroupManager.shared.sendBotMessage   (__BOT_IN_THE_PIPELINE_2026_11_09__)
//          → un message de bot dans groupMessages
//
//  No `GroupChatView` is instantiated at any point — that is the assertion.
//  Before step 2 this test could not pass: the trigger hook lived in
//  `GroupViews.sendGroupMessage` (so an inbound message reached nothing) and the
//  reply was emitted by an `.onReceive` on that same view (so with no view there
//  was no reply).
//

import XCTest
import CryptoKit
@testable import OSHI

@MainActor
final class BotDeliveryWiringTests: XCTestCase {

    private let groupId = UUID(uuidString: "B0741111-2222-3333-4444-555555555555")!
    private let me      = "TW9pS2V5QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQQ=="
    private let peer    = "UGVlcktleUJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQg=="

    private var createdBotIds: [UUID] = []
    private var savedPublicKey: String?

    override func setUp() async throws {
        try await super.setUp()
        savedPublicKey = UserDefaults.standard.string(forKey: "publicKey")
        UserDefaults.standard.set(me, forKey: "publicKey")

        var group = MessageGroup(
            id: groupId,
            name: "Refuge",
            type: .collaborative,
            adminPublicKey: me,
            members: [
                GroupMember(publicKey: me, alias: "Moi", joinedAt: Date(timeIntervalSince1970: 0), isAdmin: true),
                GroupMember(publicKey: peer, alias: "Pair", joinedAt: Date(timeIntervalSince1970: 0), isAdmin: false)
            ],
            createdAt: Date(timeIntervalSince1970: 0),
            lastActivity: Date(timeIntervalSince1970: 0)
        )
        group.setCurrentUser(me)
        MessageGroupManager.shared.groups.removeAll { $0.id == groupId }
        MessageGroupManager.shared.groups.append(group)
        MessageGroupManager.shared.groupMessages[groupId] = []
    }

    override func tearDown() async throws {
        for id in createdBotIds {
            if let bot = BotManager.shared.bots.first(where: { $0.id == id }) {
                BotManager.shared.deleteBot(bot)
            }
        }
        createdBotIds.removeAll()
        MessageGroupManager.shared.groups.removeAll { $0.id == groupId }
        MessageGroupManager.shared.groupMessages[groupId] = nil
        if let savedPublicKey {
            UserDefaults.standard.set(savedPublicKey, forKey: "publicKey")
        } else {
            UserDefaults.standard.removeObject(forKey: "publicKey")
        }
        try await super.tearDown()
    }

    // MARK: - Fixtures

    @discardableResult
    private func makeHelpBot(named name: String = "Assistant") -> OSHIBot {
        var bot = BotManager.shared.createBot(
            name: name, username: name.lowercased(), description: "",
            type: .automation, ownerPublicKey: me
        )
        bot.triggers = [
            BotTrigger(id: UUID(), type: .command, condition: "/help",
                       responseTemplate: "Commandes: /help", isActive: true)
        ]
        BotManager.shared.updateBot(bot)
        BotManager.shared.assignBot(bot.id, toGroup: groupId)
        createdBotIds.append(bot.id)
        return bot
    }

    private func deliverInbound(_ text: String, from sender: String) {
        let message = GroupMessage(
            id: UUID(),
            groupId: groupId,
            senderPublicKey: sender,
            encryptedContent: Data(text.utf8),
            timestamp: Date(),
            isRead: false,
            messageChainIndex: 0,
            plaintextContent: text
        )
        MessageGroupManager.shared.receiveGroupMessage(message)
    }

    /// Replies produced BY THE BOTS THIS TEST CREATED.
    ///
    /// Not simply "every message with a `bot:` sender": `receiveGroupMessage`
    /// also STORES the inbound message, so when the test injects one from a bot
    /// (the loop-guard case) that message would count as its own reply and the
    /// assertion would fail for the wrong reason. It did, on the first run — the
    /// end-to-end harness catching its own author before it caught the product.
    private var botReplies: [GroupMessage] {
        let mine = Set(createdBotIds.map { "bot:" + $0.uuidString.prefix(8) })
        return (MessageGroupManager.shared.groupMessages[groupId] ?? [])
            .filter { mine.contains($0.senderPublicKey) }
    }

    private func settle(until condition: @escaping () -> Bool, timeout: TimeInterval = 3) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return }
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    // MARK: - Ce que l'étape 2 prétend

    /// Le cas qui n'existait pas: un AUTRE membre tape `/help`, et le bot répond
    /// alors qu'aucune vue n'est instanciée.
    func testBotAnswersAnIncomingMessageWithNoViewOnScreen() async throws {
        let bot = makeHelpBot()

        deliverInbound("/help", from: peer)
        await settle { !self.botReplies.isEmpty }

        let replies = botReplies
        XCTAssertEqual(replies.count, 1, "le bot n'a pas répondu au message d'un autre membre")
        let body = try XCTUnwrap(replies.first?.plaintextContent)
        XCTAssertTrue(body.contains("Commandes: /help"), "réponse inattendue: \(body)")
        XCTAssertTrue(body.contains(bot.name), "la réponse doit porter le nom du bot")
        XCTAssertEqual(replies.first?.senderPublicKey, "bot:" + bot.id.uuidString.prefix(8))
    }

    /// Le coupe-boucle, de bout en bout: la sortie d'un bot revient par le même
    /// entonnoir et ne doit RIEN redéclencher.
    func testBotOutputDoesNotTriggerAnotherBot() async throws {
        makeHelpBot(named: "Premier")
        makeHelpBot(named: "Second")

        // Un message de bot qui contient le déclencheur des deux bots.
        deliverInbound("[Premier] /help", from: "bot:deadbeef")
        try? await Task.sleep(nanoseconds: 600_000_000)

        XCTAssertTrue(botReplies.isEmpty,
                      "deux bots aux mots-clés croisés se répondraient indéfiniment")
    }

    /// __BOT_IN_THE_PIPELINE_2026_11_09__ Le coupe-boucle sous sa forme RÉELLE:
    /// la réponse d'un bot d'un AUTRE membre arrive sous la vraie clé de son
    /// propriétaire (pas `bot:`), marquée `botAuthor`. Elle contient `/help` et
    /// ne doit réveiller aucun de nos bots.
    func testBotAuthoredReplyFromAnotherOwnerDoesNotTriggerOurBots() async throws {
        // KEYWORD, not command: a reply body is `[Name] …`, so a `/help` command
        // trigger (prefix match) could never fire on it and the test would pass
        // with the guard removed — it did, on the first run.
        var bot = makeHelpBot(named: "Premier")
        bot = BotManager.shared.bots.first(where: { $0.id == bot.id }) ?? bot
        bot.triggers = [
            BotTrigger(id: UUID(), type: .keyword, condition: "help",
                       responseTemplate: "Commandes: /help", isActive: true)
        ]
        BotManager.shared.updateBot(bot)

        var message = GroupMessage(
            id: UUID(), groupId: groupId, senderPublicKey: peer,
            encryptedContent: Data("[Autre] /help".utf8), timestamp: Date(),
            isRead: false, messageChainIndex: 0, plaintextContent: "[Autre] /help")
        message.botAuthor = "Autre"
        MessageGroupManager.shared.receiveGroupMessage(message)
        try? await Task.sleep(nanoseconds: 600_000_000)

        XCTAssertTrue(botReplies.isEmpty, "une réponse de bot (botAuthor) a déclenché un bot")

        // Control: the same text WITHOUT the flag does fire — so the silence
        // above is the flag's doing, not a trigger that never matched.
        deliverInbound("[Autre] /help", from: peer)
        await settle { !self.botReplies.isEmpty }
        XCTAssertEqual(botReplies.count, 1)
    }

    /// Un accusé de réception ne doit pas faire répondre un bot à la plomberie.
    func testControlPayloadDoesNotWakeABot() async throws {
        var bot = BotManager.shared.bots.first ?? makeHelpBot()
        bot = BotManager.shared.bots.first(where: { $0.id == bot.id }) ?? bot
        bot.triggers = [
            BotTrigger(id: UUID(), type: .keyword, condition: "RECEIPT",
                       responseTemplate: "…", isActive: true)
        ]
        BotManager.shared.updateBot(bot)

        deliverInbound("📬DELIVERY_RECEIPT📬ABC-123", from: peer)
        try? await Task.sleep(nanoseconds: 600_000_000)

        XCTAssertTrue(botReplies.isEmpty, "un bot a répondu à un accusé de réception")
    }

    /// Un bot désactivé reste muet — la garde la plus banale, et celle qu'on
    /// casse le plus facilement en déplaçant un branchement.
    func testDisabledBotStaysSilent() async throws {
        let bot = makeHelpBot()
        BotManager.shared.toggleBot(bot.id)
        XCTAssertFalse(try XCTUnwrap(BotManager.shared.bots.first { $0.id == bot.id }).isEnabled)

        deliverInbound("/help", from: peer)
        try? await Task.sleep(nanoseconds: 600_000_000)

        XCTAssertTrue(botReplies.isEmpty)
    }

    /// Un bot assigné à un AUTRE groupe ne parle pas ici.
    func testBotOnlyAnswersInItsOwnGroups() async throws {
        let bot = makeHelpBot()
        BotManager.shared.removeBot(bot.id, fromGroup: groupId)

        deliverInbound("/help", from: peer)
        try? await Task.sleep(nanoseconds: 600_000_000)

        XCTAssertTrue(botReplies.isEmpty)
    }
}

// MARK: - __BOT_E2E_2026_09_23__ bot-seal-v1 vectors

/// `ServerPatches/bot_e2e/test/vectors.json`, embedded as literals (spec:
/// `BOT_SEAL_SPEC.md` — "every client MUST pass the vectors").
final class BotSealedEnvelopeTests: XCTestCase {

    static let envelope = "bot:6f1c1b0e-3d2a-4c55-9a3e-1b2c3d4e5f60:eyJ0eXBlIjoiYm90X21lc3NhZ2UiLCJ2IjoiYm90LXNlYWwtdjEiLCJtZXNzYWdlSWQiOiI2ZjFjMWIwZS0zZDJhLTRjNTUtOWEzZS0xYjJjM2Q0ZTVmNjAiLCJ0aW1lc3RhbXAiOiIyMDI2LTA5LTIzVDEyOjAwOjAwLjAwMFoiLCJib3ROYW1lIjoiQm90IiwiZTJlIjpmYWxzZSwic2VhbGVkIjp7InYiOiJib3Qtc2VhbC12MSIsImFsZyI6IlgyNTUxOS1IS0RGLVNIQTI1Ni9BRVMtMjU2LUdDTSIsImJvZHkiOiJvS0dpbzZTbHBxZW9xYXFyblRvSVZEV3VJSVZBQitpbldCZWxyUVBOUG5Xd20yQUIrWDFWNXhqT1BHWHdUR1hKeVJNd0REMnNZZVU2SHJHWWFpOGxmVmY5SXg5eU95WnZ4a0xtZzlDSTRGcFdrOVRDMk1DZGQvdWFvOUdvWlltZTU2RDdHWXBFREttRnRHTzZyYWdKeVd6YXdmcnp2aVJxcDk2SVMxQS9FNDZjV3U4ZUErMjUxdHNnZ2dTaE00cGQ0QVN5SWdkdHU3cy9mdGZ1c2ZTY1d2cmoxN1c4L2Y3WW4vUUVIb01nTGZLRldYQW5vQ1lKUlIyOXc1RTVSNUQ5SUZ5TWFPZmhBbFl1OFQxN0ZsVWpweFMwMVpuUloyS25pbzFlMlQzSkZIZXI4L2pPaUtJc2lpKzErRXRLSlZ0TTNCNnRYcTNNWUdWZ09YQk5QWkR5QnFSZ1E5TmFuNWRhVlFrWW9qa1ArYmUxN283Q0JNd2p6QU1aVnZGZWRHODAzZHVnVDVzR2NMK0ExZUZMdVdSazBVVmpFYWlCTHpjT3lZMzFPNnBodXljcEc0ZTM5bmw5VXZYQ0plQmFrSWVXNmpZPSIsIndyYXBzIjp7IjMwMGM5Yzk2MDNiOTJhNGIiOiJjTlI1Tm1DUG9kYVlIazRzMUI4NFlYUll0MytCc2hwMUpKeDJBcDRkN3o2d3NiS3p0TFcydDdpNXVydmx2aWF2cThKdFN1WUFHMXZYVElvUkpPdDU0Y2hjV2JiOG11N2tuUFQ3Mk9GdUlvUURwNkx5YjJLV2Y5cDZYb009IiwiZjM1ZTU2MTYxNjBhMzBiZiI6ImF6N21kR05ZUEw0OXdJL3AwSFpjSm1iL1VoRGRVbnlkaHdYa1NTZklEVlhBd2NMRHhNWEd4OGpKeXN1ZXY1QU1jc3JRVzYvTDVUbFJ4K0FXN3VPRVp4Smw1ZUFyaGQraEg4TE53NWtUZWlBa2YrdE9sSXpqOGQ2S0ZLUT0ifX0sImJvdElkIjoiZmVlZGZhY2VjYWZlYmVlZiIsImdyb3VwSWQiOiIzRjI1MDRFMC00Rjg5LTExRDMtOUEwQy0wMzA1RTgyQzMzMDEiLCJjb250ZW50Ijoi8J+UkiBFbmNyeXB0ZWQgYm90IG1lc3NhZ2UuIFVwZGF0ZSBPU0hJIHRvIHJlYWQgaXQuIiwiYm90VG9rZW4iOiJhYmNkMTIzNC4uLiJ9"
    static let innerJSON = "{\"type\":\"bot_message\",\"messageId\":\"6f1c1b0e-3d2a-4c55-9a3e-1b2c3d4e5f60\",\"botToken\":\"abcd1234...\",\"botName\":\"Vector Bot\",\"groupId\":\"3F2504E0-4F89-11D3-9A0C-0305E82C3301\",\"groupName\":\"Vectors\",\"content\":\"héllo 🔐 vectors\",\"timestamp\":\"2026-09-23T12:00:00.000Z\",\"botId\":\"feedfacecafebeef\",\"sealedBy\":\"server\"}"
    static let recipients: [(name: String, priv: String, pub: String, kid: String)] = [
        ("alice", "dwdtCnMYpX08FsFyUbJmRd9ML4frwJkqsXf7pR25LCo=", "hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo=", "300c9c9603b92a4b"),
        ("bob", "XasIfmJKikt54X+Lg4AO5m87sSkmGLb9HC+LJ/+I4Os=", "3p7bfXt9wbTTW2HC7OQ1Nz+DQ8hbeGdNrfx+FG+IK08=", "f35e5616160a30bf"),
    ]

    private func split() throws -> (id: String, outer: [String: Any]) {
        let parts = Self.envelope.split(separator: ":", maxSplits: 2).map(String.init)
        XCTAssertEqual(parts.count, 3)
        let data = try XCTUnwrap(Data(base64Encoded: parts[2]))
        let outer = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        return (parts[1], outer)
    }

    private func key(_ i: Int) throws -> Curve25519.KeyAgreement.PrivateKey {
        try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: XCTUnwrap(Data(base64Encoded: Self.recipients[i].priv)))
    }

    func testBothRecipientsOpenTheVector() throws {
        let (id, outer) = try split()
        XCTAssertTrue(BotSealedEnvelope.isSealed(outer))
        let expected = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(Self.innerJSON.utf8)) as? NSDictionary)
        for i in 0..<2 {
            let k = try key(i)
            XCTAssertEqual(k.publicKey.rawRepresentation.base64EncodedString(), Self.recipients[i].pub)
            XCTAssertEqual(BotSealedEnvelope.kid(forPublicKey: k.publicKey.rawRepresentation), Self.recipients[i].kid)
            let inner = try BotSealedEnvelope.open(outer: outer, messageId: id, privateKey: k)
            XCTAssertEqual(inner as NSDictionary, expected, Self.recipients[i].name)
            XCTAssertEqual(inner["content"] as? String, "héllo 🔐 vectors")
            XCTAssertEqual(BotSealedEnvelope.label(inner: inner, outer: outer), .serverSealed)
        }
    }

    private func assertRejects(_ outer: [String: Any], _ id: String, _ expected: BotSealedEnvelope.OpenError,
                               file: StaticString = #filePath, line: UInt = #line) throws {
        XCTAssertThrowsError(try BotSealedEnvelope.open(outer: outer, messageId: id, privateKey: key(0)),
                             file: file, line: line) { err in
            XCTAssertEqual(err as? BotSealedEnvelope.OpenError, expected, file: file, line: line)
        }
    }

    func testTamperedBodyIsRejected() throws {
        let (id, splitOuter) = try split()
        var outer = splitOuter
        var sealed = try XCTUnwrap(outer["sealed"] as? [String: Any])
        var body = [UInt8](try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(sealed["body"] as? String))))
        body[20] ^= 0x01
        sealed["body"] = Data(body).base64EncodedString()
        outer["sealed"] = sealed
        try assertRejects(outer, id, .bodyAuthFailed)
    }

    func testWrongMessageIdIsRejected() throws {
        let (_, outer) = try split()
        let other = "6f1c1b0e-3d2a-4c55-9a3e-1b2c3d4e5f61"
        // Queue id disagrees with the outer id.
        try assertRejects(outer, other, .messageIdMismatch)
        // Outer id rewritten consistently: the AAD no longer matches.
        var rewritten = outer
        rewritten["messageId"] = other
        try assertRejects(rewritten, other, .wrapAuthFailed)
    }

    func testMissingWrapIsRejected() throws {
        let (id, splitOuter) = try split()
        var outer = splitOuter
        var sealed = try XCTUnwrap(outer["sealed"] as? [String: Any])
        var wraps = try XCTUnwrap(sealed["wraps"] as? [String: Any])
        wraps.removeValue(forKey: Self.recipients[0].kid)
        sealed["wraps"] = wraps
        outer["sealed"] = sealed
        try assertRejects(outer, id, .noWrapForThisKey)
    }

    func testOuterGroupIdMustMatchInner() throws {
        let (id, splitOuter) = try split()
        var outer = splitOuter
        outer["groupId"] = "00000000-0000-0000-0000-000000000000"
        try assertRejects(outer, id, .groupIdMismatch)
    }

    func testLegacyEnvelopeStaysLegacy() {
        let legacy: [String: Any] = ["type": "bot_message", "content": "hi", "groupId": UUID().uuidString]
        XCTAssertFalse(BotSealedEnvelope.isSealed(legacy))
        XCTAssertEqual(BotSealedEnvelope.label(inner: nil, outer: legacy), .legacyPlain)
    }
}
