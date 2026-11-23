//
//  SlashCommandTests.swift
//  OSHITests
//
//  __SLASH_MENU_2026_08_26__ Le menu `/` du compositeur.
//
//  La regle qui compte n'est pas « la liste s'affiche », c'est QUAND elle
//  s'affiche. Un menu qui reste ouvert pendant qu'on tape les arguments recouvre
//  la conversation; un menu qui refuse de s'ouvrir sur `/` seul ne repond pas a
//  la question « quelles commandes existent ? » — qui est tout l'interet.
//

import XCTest
@testable import OSHI

final class SlashCommandTests: XCTestCase {

    // MARK: - Fixtures

    private func trigger(_ condition: String,
                         type: BotTrigger.TriggerType = .command,
                         active: Bool = true) -> BotTrigger {
        BotTrigger(id: UUID(), type: type, condition: condition,
                   responseTemplate: "r", isActive: active)
    }

    private func bot(_ name: String,
                     triggers: [BotTrigger],
                     enabled: Bool = true,
                     description: String = "d",
                     emoji: String = "🤖") -> OSHIBot {
        OSHIBot(id: UUID(), token: "t", name: name, username: name.lowercased(),
                description: description, avatarEmoji: emoji, type: .automation,
                isEnabled: enabled, permissions: BotPermissions(),
                triggers: triggers, webhookURL: nil, scriptContent: nil,
                assignedGroups: [], createdAt: Date(), lastActivity: nil,
                messagesSent: 0, ownerPublicKey: "k")
    }

    /// __CASHU_2026_10_25__ `/pay` is a BUILT-IN, offered with or without bots
    /// (`SlashCommands.builtIns()`), and it wins on deduplication. Read from the
    /// source rather than written out as "/pay" so that adding a second built-in
    /// does not silently re-break the four "contributes nothing" tests below —
    /// which is exactly what happened when `/pay` landed: the Kotlin side got
    /// `SlashCommandCatalog` and six JVM tests, and these iOS expectations were
    /// left asserting the pre-`/pay` behaviour.
    private var builtIns: [String] { SlashCommands.builtIns().map(\.command) }

    private lazy var faq = bot("FAQ", triggers: [trigger("/help"), trigger("/about")])
    private lazy var stats = bot("Stats", triggers: [trigger("/stats")])
    private var bots: [OSHIBot] { [faq, stats] }

    // MARK: - Quand le menu s'ouvre

    /// Le geste de decouverte: `/` seul montre TOUT.
    func testLoneSlashOpensTheFullList() {
        XCTAssertEqual(SlashCommands.query(in: "/"), "")
        XCTAssertEqual(SlashCommands.matches(for: "/", bots: bots).map(\.command),
                       ["/about", "/help", "/pay", "/stats"])
    }

    /// On tape, ca filtre.
    func testTypingFiltersByPrefix() {
        XCTAssertEqual(SlashCommands.matches(for: "/he", bots: bots).map(\.command), ["/help"])
        XCTAssertEqual(SlashCommands.matches(for: "/s", bots: bots).map(\.command), ["/stats"])
    }

    /// La casse ne doit pas decider si une commande existe.
    func testFilteringIsCaseInsensitive() {
        XCTAssertEqual(SlashCommands.matches(for: "/HE", bots: bots).map(\.command), ["/help"])
    }

    /// LE contrat de fermeture: apres l'espace on ecrit les ARGUMENTS, le menu
    /// doit disparaitre, sinon il recouvre la conversation pendant la frappe.
    func testMenuClosesOnceASpaceIsTyped() {
        XCTAssertNil(SlashCommands.query(in: "/help "))
        XCTAssertNil(SlashCommands.query(in: "/help moi"))
        XCTAssertEqual(SlashCommands.matches(for: "/help moi", bots: bots), [])
    }

    /// Une barre au milieu d'une phrase est du texte, pas une commande.
    func testSlashMustBeTheFirstCharacter() {
        XCTAssertNil(SlashCommands.query(in: "bonjour /help"))
        XCTAssertNil(SlashCommands.query(in: " /help"))
        XCTAssertEqual(SlashCommands.matches(for: "et/ou", bots: bots), [])
    }

    /// Un message multiligne qui commence par `/` n'est plus une commande en
    /// cours de frappe.
    func testNewlineClosesTheMenu() {
        XCTAssertNil(SlashCommands.query(in: "/help\nsuite"))
    }

    /// Un prefixe qui ne correspond a rien ne doit pas laisser une barre vide.
    func testUnknownPrefixYieldsNothing() {
        XCTAssertEqual(SlashCommands.matches(for: "/zzz", bots: bots), [])
    }

    // MARK: - Ce qui entre dans la liste

    /// Seuls les declencheurs `.command` — un mot-cle n'est pas une commande.
    func testOnlyCommandTriggersAreListed() {
        let mixed = bot("Mixed", triggers: [trigger("hello", type: .keyword),
                                            trigger("86400", type: .schedule),
                                            trigger("/go")])
        XCTAssertEqual(SlashCommands.commands(from: [mixed]).map(\.command), ["/go", "/pay"])
    }

    /// `BotManager` accepte la condition avec ou sans barre; le menu doit
    /// normaliser, sinon `/help` et `help` s'affichent comme deux commandes.
    func testConditionsAreNormalisedAndDeduplicated() {
        let a = bot("A", triggers: [trigger("help")])
        let b = bot("B", triggers: [trigger("/help")])
        XCTAssertEqual(SlashCommands.commands(from: [a, b]).map(\.command), ["/help", "/pay"])
    }

    /// Un bot eteint ne propose rien.
    func testDisabledBotContributesNothing() {
        let off = bot("Off", triggers: [trigger("/secret")], enabled: false)
        XCTAssertEqual(SlashCommands.commands(from: [off]).map(\.command), builtIns)
    }

    /// Un declencheur desactive non plus.
    func testInactiveTriggerContributesNothing() {
        let b = bot("B", triggers: [trigger("/off", active: false)])
        XCTAssertEqual(SlashCommands.commands(from: [b]).map(\.command), builtIns)
    }

    /// Une condition vide ou bavarde ne fabrique pas une fausse ligne.
    func testJunkConditionsAreSkipped() {
        let junk = bot("J", triggers: [trigger(""), trigger("/"), trigger("two words")])
        XCTAssertEqual(SlashCommands.commands(from: [junk]).map(\.command), builtIns)
    }

    /// Aucun bot: la barre montre quand meme les commandes INTEGREES.
    ///
    /// Ce test s'appelait `testNoBotsMeansNoBar` et exigeait une liste vide.
    /// Depuis `/pay` c'est faux, et le nom etait devenu trompeur: la regle
    /// « jamais de barre VIDE » porte sur le resultat, pas sur l'absence de
    /// bots. Une install sans aucun bot a desormais quelque chose a proposer.
    func testWithNoBotsTheBarStillOffersTheBuiltIns() {
        XCTAssertEqual(SlashCommands.matches(for: "/", bots: []).map(\.command), builtIns)
        XCTAssertFalse(builtIns.isEmpty, "sinon ce test ne verifie rien")
    }

    /// La vraie regle « pas de barre »: ce n'est pas l'absence de bots, c'est
    /// l'absence de CORRESPONDANCE. `[]` veut dire pas de barre du tout.
    func testNoMatchStillMeansNoBar() {
        XCTAssertEqual(SlashCommands.matches(for: "/zzz", bots: []), [])
        XCTAssertEqual(SlashCommands.matches(for: "bonjour", bots: bots), [])
    }

    /// Une commande INTEGREE ne peut pas etre dupliquee ni detournee par un bot
    /// qui declare le meme nom: `commands(from:)` insere les integrees d'abord
    /// et la deduplication les fait gagner.
    func testABotCannotDuplicateOrHijackABuiltIn() {
        let impostor = bot("Impostor", triggers: [trigger("/pay")], description: "pas OSHI")
        let out = SlashCommands.commands(from: [impostor])
        XCTAssertEqual(out.map(\.command), builtIns)
        XCTAssertEqual(out.first?.botID, SlashCommands.builtInBotID,
                       "la ligne /pay doit rester celle d'OSHI, pas celle du bot")
    }

    // MARK: - Ce qu'on obtient en choisissant

    /// L'espace finale est le contrat: elle amorce l'argument ET referme le
    /// menu par la regle de `query(in:)`.
    func testCompletionEndsWithASpaceAndClosesTheMenu() throws {
        let cmd = SlashCommands.commands(from: [faq]).first { $0.command == "/help" }
        let text = SlashCommands.completion(for: try XCTUnwrap(cmd))
        XCTAssertEqual(text, "/help ")
        XCTAssertNil(SlashCommands.query(in: text),
                     "choisir une commande doit refermer la liste")
    }
}
