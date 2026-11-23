//
//  BotDirectoryTests.swift
//  OSHITests
//
//  __BOT_DIRECTORY_2026_08_16__ — étape 5.
//
//  The directory is the first moment the bot ecosystem exists for anyone other
//  than the person who wrote the bot. Its whole value rests on one property:
//  a card cannot say anything the manifest does not, and cannot stay silent
//  about something the manifest declares. Everything below pins that.
//
//  The `worksOffline` column is also the one no competitor can print — a
//  Telegram bot is an HTTP endpoint on someone's server by construction.
//

import XCTest
@testable import OSHI

final class BotDirectoryTests: XCTestCase {

    private func bot(type: BotType, webhook: String? = nil, name: String = "Assistant") -> OSHIBot {
        OSHIBot(
            id: UUID(), token: "0123456789abcdef0123456789abcdef",
            name: name, username: name.lowercased(), description: "",
            avatarEmoji: "🤖", type: type, isEnabled: true,
            permissions: BotPermissions(), triggers: [],
            webhookURL: webhook, scriptContent: nil, assignedGroups: [],
            createdAt: Date(timeIntervalSince1970: 0), lastActivity: nil,
            messagesSent: 0, ownerPublicKey: "owner"
        )
    }

    // MARK: - La colonne incopiable

    func testWorksOfflineIsExactlyWhereTheCodeRuns() {
        let entries = BotDirectory.entries(for: [
            bot(type: .automation, name: "Local"),
            bot(type: .scheduled, name: "Planifie"),
            bot(type: .moderator, name: "Modo"),
            bot(type: .webhook, webhook: "https://x.test", name: "Distant"),
        ])
        XCTAssertEqual(entries.filter { $0.worksOffline }.count, 3)
        XCTAssertEqual(entries.first { !$0.worksOffline }?.manifest.name, "Distant")
    }

    func testOfflineFilterKeepsOnlyLocalBots() {
        let entries = BotDirectory.entries(for: [
            bot(type: .automation, name: "Local"),
            bot(type: .webhook, webhook: "https://x.test", name: "Distant"),
        ])
        let offline = BotDirectory.filter(entries, offlineOnly: true)
        XCTAssertEqual(offline.count, 1)
        XCTAssertEqual(offline.first?.manifest.name, "Local")
    }

    func testFiltersCompose() {
        let entries = BotDirectory.entries(for: [
            bot(type: .automation, name: "Local"),
            bot(type: .webhook, webhook: "https://x.test", name: "Distant"),
        ])
        XCTAssertEqual(BotDirectory.filter(entries, offlineOnly: true, sealedOnly: true).count, 1)
        XCTAssertTrue(BotDirectory.filter(entries, offlineOnly: false, sealedOnly: true)
                        .allSatisfy { $0.isSealed })
    }

    // MARK: - Une fiche ne peut pas mentir

    /// Un bot scellé n'a rien à divulguer, et surtout pas un point de sortie.
    func testSealedEntryDisclosesNothingAndCarriesNoEndpoint() {
        let entry = BotDirectory.entries(for: [bot(type: .automation)])[0]
        XCTAssertTrue(entry.isSealed)
        XCTAssertTrue(entry.forwards.isEmpty)
        XCTAssertNil(entry.manifest.remote)
        XCTAssertNil(entry.offlinePolicy, "la question ne se pose pas pour un bot local")
        XCTAssertFalse(entry.hasUndeclaredForwarding)
    }

    /// Un bot distant imprime mot pour mot ce qui quitte l'appareil.
    func testRemoteEntryPrintsWhatLeavesTheDevice() throws {
        let entry = BotDirectory.entries(for: [bot(type: .webhook, webhook: "https://bots.exemple.fr/x")])[0]
        XCTAssertFalse(entry.isSealed)
        XCTAssertFalse(entry.worksOffline)
        XCTAssertEqual(entry.forwards, ["matched_text", "group_id", "sender_public_key"])
        XCTAssertEqual(try XCTUnwrap(entry.offlinePolicy), .queue)
        XCTAssertFalse(entry.hasUndeclaredForwarding)
    }

    /// L'état dangereux: distant ET muet. Une liste vide ne doit pas se lire
    /// comme « rien ne sort » sur une fiche qui ne distingue pas « vide » de
    /// « pas répondu ».
    func testRemoteBotThatDeclaresNothingIsFlagged() {
        let entry = BotDirectory.entries(for: [bot(type: .webhook, webhook: nil)])[0]
        XCTAssertFalse(entry.worksOffline)
        XCTAssertTrue(entry.forwards.isEmpty)
        XCTAssertTrue(entry.hasUndeclaredForwarding,
                      "distant sans divulgation doit être signalé, pas passé sous silence")
    }

    /// La propriété qui tient tout: aucune fiche ne peut être à la fois scellée
    /// et divulguer quelque chose, ni distante et se présenter comme scellée.
    func testNoEntryCanBeBothSealedAndForwarding() {
        let entries = BotDirectory.entries(for: BotType.allCases.map {
            bot(type: $0, webhook: $0 == .webhook ? "https://x.test" : nil)
        })
        for entry in entries {
            if entry.isSealed {
                XCTAssertTrue(entry.forwards.isEmpty)
                XCTAssertTrue(entry.worksOffline, "scellé = tourne ici = marche hors réseau")
            } else {
                XCTAssertFalse(entry.worksOffline)
            }
        }
    }

    /// La fiche ne porte aucune prose: chaque chaîne que l'utilisateur lira est
    /// construite par la vue à partir de ces faits, avec des clés qui existent
    /// déjà dans les 34 `.lproj`. Inventer une clé ici, c'est 34 fichiers à
    /// éditer et la clé brute affichée dans la langue qu'on aura oubliée.
    func testEntryCarriesFactsNotSentences() {
        let entry = BotDirectory.entries(for: [bot(type: .webhook, webhook: "https://x.test")])[0]
        // Les seules chaînes sont celles que l'auteur du bot a écrites lui-même,
        // plus les identifiants de champ — jamais une phrase produite par nous.
        XCTAssertEqual(entry.manifest.name, "Assistant")
        XCTAssertEqual(entry.manifest.handle, "assistant")
        for forward in entry.forwards {
            XCTAssertFalse(forward.contains(" "), "« \(forward) » ressemble à une phrase, pas à un identifiant")
        }
    }

    func testEmptyDirectory() {
        XCTAssertTrue(BotDirectory.entries(for: []).isEmpty)
        XCTAssertTrue(BotDirectory.filter([], offlineOnly: true).isEmpty)
    }
}
