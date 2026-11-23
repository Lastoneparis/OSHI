//
//  BotNoBotHintTests.swift
//  OSHITests
//
//  __BOT_NO_BOT_HINT_2026_08_17__
//
//  Mesuré avant d'être écrit: deux téléphones, quatre groupes, `totalBots=0` sur
//  chaque ligne du log. L'app livre un MODÈLE `/help`, donc la commande a l'air
//  de quelque chose que l'app devrait traiter — et rien ne disait le contraire.
//  L'utilisateur a tapé, rien ne s'est passé, et ni lui ni moi ne pouvions
//  distinguer « il n'y a pas de bot » de « le bot est cassé ».
//
//  Le risque de ce genre d'indice est de devenir du harcèlement. D'où deux
//  règles asserties ici: étroit sur ce qui compte comme commande, et UNE SEULE
//  FOIS par groupe.
//

import XCTest
@testable import OSHI

// MARK: - Niveau 1 — ce qui compte comme une commande

final class BotCommandShapeTests: XCTestCase {

    func testASlashFollowedByALetterIsACommand() {
        XCTAssertTrue(BotManager.looksLikeACommand("/help"))
        XCTAssertTrue(BotManager.looksLikeACommand("/rules maintenant"))
        XCTAssertTrue(BotManager.looksLikeACommand("  /about  "), "les espaces autour ne changent rien")
    }

    /// Le sens permissif est celui qui coûte cher: harceler quelqu'un à propos de
    /// bots parce qu'il a collé un fragment d'URL.
    func testEverythingElseIsNot() {
        for text in ["help", "", "   ", "/", "//", "/1234", "/ help", "et/ou", "🙂 /help"] {
            XCTAssertFalse(BotManager.looksLikeACommand(text), "« \(text) » ne doit pas compter comme une commande")
        }
    }

    /// Les payloads de contrôle commencent par un sentinelle emoji, jamais par
    /// une barre oblique — ils ne peuvent donc pas déclencher l'indice.
    func testControlPayloadsAreNotCommands() {
        XCTAssertFalse(BotManager.looksLikeACommand("📬DELIVERY_RECEIPT📬abc"))
        XCTAssertFalse(BotManager.looksLikeACommand("📍LOCATION📍{}"))
    }
}

// MARK: - Niveau 3 — que ça marche, et une seule fois

@MainActor
final class BotNoBotHintTests: XCTestCase {

    private let groupId = UUID(uuidString: "B0745555-6666-7777-8888-999999999999")!
    private let otherGroupId = UUID(uuidString: "B0746666-7777-8888-9999-AAAAAAAAAAAA")!
    private let me = "TW9pSGludEtleUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQQ=="

    private var createdBotIds: [UUID] = []

    override func setUp() async throws {
        try await super.setUp()
        // L'indice est « une fois par groupe », persisté: repartir propre.
        UserDefaults.standard.removeObject(forKey: "oshi_bot_hint_shown_groups_v1")
    }

    override func tearDown() async throws {
        for id in createdBotIds {
            if let bot = BotManager.shared.bots.first(where: { $0.id == id }) { BotManager.shared.deleteBot(bot) }
        }
        createdBotIds.removeAll()
        UserDefaults.standard.removeObject(forKey: "oshi_bot_hint_shown_groups_v1")
        try await super.tearDown()
    }

    private func makeBotAssigned(to group: UUID, enabled: Bool = true, canRead: Bool = true) {
        var bot = BotManager.shared.createBot(name: "Aide", username: "aide", description: "",
                                              type: .automation, ownerPublicKey: me)
        bot.isEnabled = enabled
        bot.permissions.canReadMessages = canRead
        BotManager.shared.updateBot(bot)
        BotManager.shared.assignBot(bot.id, toGroup: group)
        createdBotIds.append(bot.id)
    }

    /// Le cas vécu: aucun bot nulle part, une commande tapée.
    func testHintFiresWhenNoBotExists() {
        XCTAssertTrue(BotManager.shared.shouldShowNoBotHint(for: "/help", groupId: groupId))
    }

    /// Et UNE SEULE FOIS. Un indice qui revient n'est plus un indice.
    func testHintNeverFiresTwiceForTheSameGroup() {
        XCTAssertTrue(BotManager.shared.shouldShowNoBotHint(for: "/help", groupId: groupId))
        BotManager.shared.markNoBotHintShown(groupId: groupId)
        XCTAssertFalse(BotManager.shared.shouldShowNoBotHint(for: "/help", groupId: groupId))
        XCTAssertFalse(BotManager.shared.shouldShowNoBotHint(for: "/rules", groupId: groupId),
                       "une autre commande dans le même groupe n'a rien de neuf à apprendre")
    }

    /// Mais chaque groupe a droit à son explication.
    func testAnotherGroupStillGetsItsOwnHint() {
        BotManager.shared.markNoBotHintShown(groupId: groupId)
        XCTAssertTrue(BotManager.shared.shouldShowNoBotHint(for: "/help", groupId: otherGroupId))
    }

    /// Un bot qui écoute = pas d'indice: il y a une vraie réponse à attendre.
    func testNoHintWhenAUsableBotIsPresent() {
        makeBotAssigned(to: groupId)
        XCTAssertFalse(BotManager.shared.shouldShowNoBotHint(for: "/help", groupId: groupId))
    }

    /// Un bot désactivé ou aveugle ne répondra pas — l'indice est donc juste.
    func testDisabledOrUnreadableBotStillCountsAsNoBot() {
        makeBotAssigned(to: groupId, enabled: false)
        XCTAssertTrue(BotManager.shared.shouldShowNoBotHint(for: "/help", groupId: groupId),
                      "un bot éteint ne répondra pas: ne pas prétendre le contraire")

        BotManager.shared.markNoBotHintShown(groupId: groupId)
        makeBotAssigned(to: otherGroupId, canRead: false)
        XCTAssertTrue(BotManager.shared.shouldShowNoBotHint(for: "/help", groupId: otherGroupId))
    }

    /// Un bot assigné AILLEURS ne compte pas pour ce groupe-ci.
    func testBotInAnotherGroupDoesNotSuppressTheHint() {
        makeBotAssigned(to: otherGroupId)
        XCTAssertTrue(BotManager.shared.shouldShowNoBotHint(for: "/help", groupId: groupId))
    }

    /// Un message ordinaire ne déclenche jamais rien.
    func testOrdinaryMessagesNeverTriggerTheHint() {
        XCTAssertFalse(BotManager.shared.shouldShowNoBotHint(for: "salut tout le monde", groupId: groupId))
        XCTAssertFalse(BotManager.shared.shouldShowNoBotHint(for: "help", groupId: groupId),
                       "sans barre oblique, ce n'est pas une tentative de commande")
    }
}
