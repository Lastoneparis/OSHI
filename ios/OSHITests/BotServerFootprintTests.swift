//
//  BotServerFootprintTests.swift
//  OSHITests
//
//  __BOT_LOCAL_BOTS_LEAVE_NO_TRACE_2026_08_16__ — étape 4.
//
//  Adding one bot to a group used to put that group's NAME and the FULL LIST OF
//  ITS MEMBERS' PUBLIC KEYS on the server, in cleartext, and made the bot's
//  traffic queue per member as base64 JSON — also cleartext. For an app whose
//  entire argument is end-to-end encryption, that is the product, not a detail.
//
//  A `local` bot buys nothing from any of it: its triggers run on the device and
//  its replies leave through the normal encrypted group path. The registration
//  existed so an OUTSIDE process could drive the bot — which is the definition of
//  a remote one.
//
//  These tests pin the rule that decides it. They do NOT hit the network: what
//  matters is which bots are eligible at all, and that is a pure predicate.
//

import XCTest
@testable import OSHI

final class BotServerFootprintTests: XCTestCase {

    private func bot(type: BotType, webhook: String? = nil) -> OSHIBot {
        OSHIBot(
            id: UUID(), token: "0123456789abcdef0123456789abcdef",
            name: "Assistant", username: "assistant", description: "",
            avatarEmoji: "🤖", type: type, isEnabled: true,
            permissions: BotPermissions(), triggers: [],
            webhookURL: webhook, scriptContent: nil, assignedGroups: [UUID()],
            createdAt: Date(timeIntervalSince1970: 0), lastActivity: nil,
            messagesSent: 0, ownerPublicKey: "owner"
        )
    }

    /// La règle: seul un bot distant a quoi que ce soit à faire sur le serveur.
    func testOnlyRemoteBotsAreEligibleForRegistration() {
        XCTAssertTrue(BotManager.needsServerRegistration(bot(type: .webhook, webhook: "https://x.test")))
        for type in [BotType.automation, .scheduled, .moderator] {
            XCTAssertFalse(BotManager.needsServerRegistration(bot(type: type)),
                           "\(type) tourne sur l'appareil — le serveur n'a pas à connaître ce groupe")
        }
    }

    /// Un bot webhook SANS URL reste distant: c'est un bot dont le pilote est
    /// externe et pas encore configuré, pas un bot local. Le traiter comme local
    /// lui retirerait son jeton en douce.
    func testWebhookWithoutURLIsStillRemote() {
        XCTAssertTrue(BotManager.needsServerRegistration(bot(type: .webhook, webhook: nil)))
    }

    /// La règle est exactement le runtime du manifeste — pas une seconde liste
    /// qui pourrait diverger de la première, ce que ce plan passe son temps à
    /// éliminer.
    func testEligibilityIsExactlyTheManifestRuntime() {
        for type in BotType.allCases {
            let b = bot(type: type, webhook: type == .webhook ? "https://x.test" : nil)
            XCTAssertEqual(BotManager.needsServerRegistration(b),
                           BotManager.manifest(for: b).runtime == .remote,
                           "\(type): la règle serveur doit rester la même que le manifeste")
        }
    }

    /// Et donc: ce qu'un bot local expose côté serveur est exactement ce que le
    /// manifeste appelle « sealed ».
    func testSealedBotsHaveNoServerFootprint() {
        for type in BotType.allCases {
            let b = bot(type: type, webhook: type == .webhook ? "https://x.test" : nil)
            let m = BotManager.manifest(for: b)
            if m.confidentiality == "sealed" {
                XCTAssertFalse(BotManager.needsServerRegistration(b),
                               "un bot annoncé « sealed » ne peut pas déposer le nom du groupe et ses membres sur le serveur")
            }
        }
    }
}
