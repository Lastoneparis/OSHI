//
//  BotCommandsTests.swift
//  OSHITests
//
//  __BOT_COMMANDS_2026_11_24__ The command model shared with Android/desktop
//  and the server (`/name` a-z0-9_ ≤ 32, description ≤ 256, ≤ 100 commands),
//  the `/` menu content and keyboard navigation, and the owner's editor.
//

import XCTest
@testable import OSHI

@MainActor
final class BotCommandsTests: XCTestCase {

    // MARK: Fixtures

    private func cmd(_ condition: String, description: String? = nil,
                     active: Bool = true, reply: String = "r") -> BotTrigger {
        BotTrigger(id: UUID(), type: .command, condition: condition,
                   responseTemplate: reply, isActive: active, commandDescription: description)
    }

    private func keyword(_ word: String) -> BotTrigger {
        BotTrigger(id: UUID(), type: .keyword, condition: word, responseTemplate: "k", isActive: true)
    }

    private func bot(_ name: String = "Quiz", triggers: [BotTrigger] = [],
                     description: String = "A quiz bot", enabled: Bool = true,
                     type: BotType = .automation) -> OSHIBot {
        OSHIBot(id: UUID(), token: "tok-\(name)", name: name, username: name.lowercased(),
                description: description, avatarEmoji: "🎲", type: type,
                isEnabled: enabled, permissions: BotPermissions(),
                triggers: triggers, webhookURL: nil, scriptContent: nil,
                assignedGroups: [], createdAt: Date(), lastActivity: nil,
                messagesSent: 0, ownerPublicKey: "k")
    }

    // MARK: Names and descriptions

    func testNormalizeStripsSlashSpacesAndCase() {
        XCTAssertEqual(BotCommandSpec.normalizeName("  /Help "), "help")
        XCTAssertEqual(BotCommandSpec.normalizeName("//start"), "start")
    }

    func testNameRulesMatchTheServer() {
        XCTAssertNil(BotCommandSpec.problem(forName: "new_game2"))
        XCTAssertNil(BotCommandSpec.problem(forName: "/HELP"), "uppercase is normalised, not refused")
        XCTAssertNil(BotCommandSpec.problem(forName: String(repeating: "a", count: 32)))
        XCTAssertEqual(BotCommandSpec.problem(forName: String(repeating: "a", count: 33)), .tooLong)
        XCTAssertEqual(BotCommandSpec.problem(forName: "/"), .empty)
        XCTAssertEqual(BotCommandSpec.problem(forName: ""), .empty)
        XCTAssertEqual(BotCommandSpec.problem(forName: "foo bar"), .invalidCharacters)
        XCTAssertEqual(BotCommandSpec.problem(forName: "café"), .invalidCharacters)
        XCTAssertEqual(BotCommandSpec.problem(forName: "a-b"), .invalidCharacters)
    }

    func testSanitizeWhileTypingKeepsOnlyWhatTheServerAccepts() {
        XCTAssertEqual(BotCommandSpec.sanitizeWhileTyping("/New Game-1!"), "new_game_1")
        XCTAssertEqual(BotCommandSpec.sanitizeWhileTyping("Café"), "caf")
        XCTAssertEqual(BotCommandSpec.sanitizeWhileTyping(String(repeating: "x", count: 40)).count, 32)
        XCTAssertNil(BotCommandSpec.problem(forName: BotCommandSpec.sanitizeWhileTyping("Hello World")))
    }

    func testDescriptionLimit() {
        XCTAssertNil(BotCommandSpec.problem(forDescription: String(repeating: "d", count: 256)))
        XCTAssertEqual(BotCommandSpec.problem(forDescription: String(repeating: "d", count: 257)), .descriptionTooLong)
    }

    // MARK: Triggers <-> commands

    func testCommandsKeepOrderSkipInactiveInvalidAndDuplicates() {
        let b = bot(triggers: [cmd("/zeta", description: "Last letter"), keyword("hi"),
                               cmd("/alpha"), cmd("/off", active: false),
                               cmd("/bad name"), cmd("ALPHA")])
        let list = BotCommandList.commands(of: b)
        XCTAssertEqual(list.map(\.name), ["zeta", "alpha"])
        XCTAssertEqual(list[0].description, "Last letter")
        XCTAssertEqual(list[1].description, "A quiz bot", "falls back to the bot's description")
    }

    func testUpsertAddsACommandTrigger() throws {
        let start = [keyword("hi")]
        let out = try BotCommandList.upsert(start, editing: nil, name: "/Roll", description: " Roll a die ",
                                            reply: "🎲").get()
        XCTAssertEqual(out.count, 2)
        XCTAssertEqual(out[0], start[0])
        XCTAssertEqual(out[1].type, .command)
        XCTAssertEqual(out[1].condition, "/roll")
        XCTAssertEqual(out[1].commandDescription, "Roll a die")
        XCTAssertEqual(out[1].responseTemplate, "🎲")
        XCTAssertTrue(out[1].isActive)
    }

    func testUpsertRefusesDuplicatesInvalidAndTooMany() {
        let existing = [cmd("/help")]
        XCTAssertEqual(failure(BotCommandList.upsert(existing, editing: nil, name: "HELP", description: "", reply: "")), .duplicate)
        XCTAssertEqual(failure(BotCommandList.upsert(existing, editing: nil, name: "two words", description: "", reply: "")), .invalidCharacters)
        XCTAssertEqual(failure(BotCommandList.upsert(existing, editing: nil, name: "x",
                                                     description: String(repeating: "d", count: 300), reply: "")), .descriptionTooLong)
        let full = (0..<100).map { cmd("/c\($0)") }
        XCTAssertEqual(failure(BotCommandList.upsert(full, editing: nil, name: "one_more", description: "", reply: "")), .tooMany)
        // Editing one of the 100 is still allowed.
        XCTAssertNil(failure(BotCommandList.upsert(full, editing: full[0].id, name: "renamed", description: "", reply: "")))
    }

    func testUpsertEditKeepsIdentityAndMayKeepItsOwnName() throws {
        let t = cmd("/help", description: "old")
        let other = keyword("x")
        let out = try BotCommandList.upsert([other, t], editing: t.id, name: "help",
                                            description: "", reply: "new reply").get()
        XCTAssertEqual(out.map(\.id), [other.id, t.id])
        XCTAssertEqual(out[1].condition, "/help")
        XCTAssertNil(out[1].commandDescription, "an emptied description is removed, not stored as \"\"")
        XCTAssertEqual(out[1].responseTemplate, "new reply")
    }

    func testStartersOnlyOfferWhatIsMissing() {
        XCTAssertEqual(BotCommandList.starters(existing: []), ["start", "help"])
        XCTAssertEqual(BotCommandList.starters(existing: [BotCommandSpec(name: "help", description: "")]), ["start"])
    }

    // MARK: Storage compatibility

    func testATriggerFromAnOlderBuildDecodesAndEncodesUnchanged() throws {
        let old = #"{"id":"6F1C2E2A-7A43-4A8C-9C71-111111111111","type":"command","condition":"/help","responseTemplate":"r","isActive":true}"#
        let t = try JSONDecoder().decode(BotTrigger.self, from: Data(old.utf8))
        XCTAssertNil(t.commandDescription)
        let back = try JSONSerialization.jsonObject(with: JSONEncoder().encode(t)) as? [String: Any]
        XCTAssertNil(back?["commandDescription"], "no new key on the wire when there is no description")

        var described = t
        described.commandDescription = "Help"
        let round = try JSONDecoder().decode(BotTrigger.self, from: JSONEncoder().encode(described))
        XCTAssertEqual(round.commandDescription, "Help")
    }

    // MARK: The `/` menu

    func testBotCommandsCarryTheirOwnDescriptionAndSendOnPick() {
        let b = bot(triggers: [cmd("/roll", description: "Roll a die"), cmd("/score")])
        let all = SlashCommands.commands(from: [b])
        let roll = all.first { $0.command == "/roll" }
        XCTAssertEqual(roll?.description, "Roll a die")
        XCTAssertEqual(roll?.sendsOnPick, true)
        XCTAssertEqual(all.first { $0.command == "/score" }?.description, "A quiz bot")
        for builtIn in SlashCommands.builtIns() {
            XCTAssertFalse(all.first { $0.command == builtIn.command }?.sendsOnPick ?? true,
                           "\(builtIn.command) takes an argument: it must fill the composer, not send")
        }
    }

    func testMenuInAChatWithoutBotsIsExactlyTheOldMenu() {
        XCTAssertNil(SlashMenuContent.make(for: "hello", bots: []))
        XCTAssertNil(SlashMenuContent.make(for: "/zzz", bots: []), "no new 'no match' row in ordinary chats")
        let menu = SlashMenuContent.make(for: "/", bots: [])
        XCTAssertEqual(menu?.commands, SlashCommands.matches(for: "/", bots: []))
        XCTAssertNil(menu?.hint)
    }

    func testMenuSaysWhenTheBotHasNoCommandsYet() {
        let b = bot(triggers: [keyword("hi")])
        let menu = SlashMenuContent.make(for: "/", bots: [b])
        XCTAssertEqual(menu?.hint, .botsHaveNoCommands(botName: "Quiz", botID: b.id))
        // Once something is typed, the hint gives way to the filter.
        XCTAssertNotEqual(SlashMenuContent.make(for: "/p", bots: [b])?.hint, .botsHaveNoCommands(botName: "Quiz", botID: b.id))
    }

    func testMenuSaysNothingMatchesInsteadOfVanishing() {
        let b = bot(triggers: [cmd("/roll")])
        let menu = SlashMenuContent.make(for: "/zz", bots: [b])
        XCTAssertEqual(menu?.commands, [])
        XCTAssertEqual(menu?.hint, .noMatch(query: "zz"))
        XCTAssertNil(SlashMenuContent.make(for: "/roll now", bots: [b]), "arguments close the menu")
    }

    func testMenuFiltersAsYouType() {
        let b = bot(triggers: [cmd("/roll"), cmd("/rules"), cmd("/score")])
        XCTAssertEqual(SlashMenuContent.make(for: "/r", bots: [b])?.commands.map(\.command), ["/roll", "/rules"])
        XCTAssertEqual(SlashMenuContent.make(for: "/RU", bots: [b])?.commands.map(\.command), ["/rules"])
        XCTAssertNil(SlashMenuContent.make(for: "/r", bots: [b])?.hint)
    }

    func testADisabledBotCountsAsNoBot() {
        let off = bot(triggers: [cmd("/roll")], enabled: false)
        XCTAssertNil(SlashMenuContent.make(for: "/zz", bots: [off]))
    }

    // MARK: Keyboard navigation

    func testArrowsWrapAndStartAtTheEnds() {
        XCTAssertEqual(SlashMenuNavigation.move(nil, by: 1, count: 3), 0)
        XCTAssertEqual(SlashMenuNavigation.move(nil, by: -1, count: 3), 2)
        XCTAssertEqual(SlashMenuNavigation.move(2, by: 1, count: 3), 0)
        XCTAssertEqual(SlashMenuNavigation.move(0, by: -1, count: 3), 2)
        XCTAssertEqual(SlashMenuNavigation.move(1, by: 1, count: 3), 2)
        XCTAssertEqual(SlashMenuNavigation.move(5, by: 1, count: 3), 0, "a stale index after filtering restarts")
        XCTAssertNil(SlashMenuNavigation.move(0, by: 1, count: 0))
    }

    func testReturnTakesTheHighlightElseTheFirstRow() {
        XCTAssertEqual(SlashMenuNavigation.target(nil, count: 4), 0)
        XCTAssertEqual(SlashMenuNavigation.target(2, count: 4), 2)
        XCTAssertEqual(SlashMenuNavigation.target(9, count: 4), 0)
        XCTAssertNil(SlashMenuNavigation.target(nil, count: 0))
    }

    // MARK: Wire format

    func testSetMyCommandsBodyIsWhatTheServerParses() throws {
        let body = HTTPBotCommandsService.body([BotCommandSpec(name: "roll", description: "Roll")], token: "T")
        XCTAssertEqual(body["token"] as? String, "T")
        let cmds = try XCTUnwrap(body["commands"] as? [[String: String]])
        XCTAssertEqual(cmds, [["command": "roll", "description": "Roll"]])
    }

    func testGetMyCommandsParsingIsLenientOnSlashStrictOnValidity() throws {
        let json = #"{"success":true,"commands":[{"command":"/Roll","description":"Roll"},{"command":"bad name"},{"command":"help"}]}"#
        let list = try HTTPBotCommandsService.parse(Data(json.utf8))
        XCTAssertEqual(list.map(\.name), ["roll", "help"])
        XCTAssertEqual(list[1].description, "")
        XCTAssertThrowsError(try HTTPBotCommandsService.parse(Data(#"{"success":false,"error":"Invalid bot token"}"#.utf8)))
    }

    // MARK: Editor view-model

    private final class FakeService: BotCommandsService {
        var sent: [[BotCommandSpec]] = []
        var fail = false
        func setCommands(_ commands: [BotCommandSpec], token: String) async throws {
            sent.append(commands)
            if fail { throw BotCommandsServiceError.http(500) }
        }
        func commands(token: String) async throws -> [BotCommandSpec] { sent.last ?? [] }
    }

    private func waitForSync(_ model: BotCommandsEditorModel) async {
        for _ in 0..<100 where model.sync == .syncing || model.sync == .idle {
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
    }

    func testLocalBotCommandsNeverLeaveTheDevice() async {
        let service = FakeService()
        var saved: [OSHIBot] = []
        let model = BotCommandsEditorModel(bot: bot(), service: service, isRemote: false) { saved.append($0) }
        XCTAssertEqual(model.sync, .localOnly)
        XCTAssertNil(model.save(editing: nil, name: "roll", description: "Roll a die", reply: "🎲"))
        await waitForSync(model)
        XCTAssertEqual(saved.last.map { BotCommandList.commands(of: $0).map(\.name) }, ["roll"])
        XCTAssertTrue(service.sent.isEmpty)
        XCTAssertEqual(model.sync, .localOnly)
    }

    func testRemoteBotPushesTheWholeListAfterEachChange() async {
        let service = FakeService()
        let model = BotCommandsEditorModel(bot: bot(type: .webhook), service: service, isRemote: true) { _ in }
        model.save(editing: nil, name: "roll", description: "Roll", reply: "")
        await waitForSync(model)
        model.save(editing: nil, name: "score", description: "Score", reply: "")
        await waitForSync(model)
        XCTAssertEqual(service.sent.last?.map(\.name), ["roll", "score"])
        XCTAssertEqual(model.sync, .synced)

        service.fail = true
        model.remove(triggerID: model.commandTriggers[0].id)
        await waitForSync(model)
        XCTAssertEqual(model.sync, .failed)
        XCTAssertEqual(model.commands.map(\.name), ["score"], "a failed sync keeps the local edit")
    }

    func testRejectedSaveChangesNothing() {
        var saves = 0
        let model = BotCommandsEditorModel(bot: bot(triggers: [cmd("/help")]), service: FakeService(),
                                           isRemote: false) { _ in saves += 1 }
        XCTAssertEqual(model.save(editing: nil, name: "Help", description: "", reply: ""), .duplicate)
        XCTAssertEqual(saves, 0)
        XCTAssertEqual(model.commandTriggers.count, 1)
    }

    func testMoveReordersCommandsAndLeavesOtherTriggersInPlace() {
        let k = keyword("hi")
        let a = cmd("/a"), b = cmd("/b"), c = cmd("/c")
        let model = BotCommandsEditorModel(bot: bot(triggers: [a, k, b, c]), service: FakeService(),
                                           isRemote: false) { _ in }
        model.move(fromOffsets: IndexSet(integer: 2), toOffset: 0)
        XCTAssertEqual(model.bot.triggers.map(\.id), [c.id, k.id, a.id, b.id])
        model.remove(atOffsets: IndexSet(integer: 0))
        XCTAssertEqual(model.bot.triggers.map(\.id), [k.id, a.id, b.id])
    }

    func testStarterAddsADescribedCommand() {
        let model = BotCommandsEditorModel(bot: bot(), service: FakeService(), isRemote: false) { _ in }
        model.addStarter("help")
        XCTAssertEqual(model.commands.map(\.name), ["help"])
        XCTAssertFalse(model.commands[0].description.isEmpty)
        XCTAssertEqual(model.starters, ["start"])
    }

    // MARK: Strings

    func testEveryNewStringExistsInEveryLocale() throws {
        let bundle = Bundle(for: OshiAppDelegate.self)
        let enPath = try XCTUnwrap(bundle.path(forResource: "Localizable", ofType: "strings", inDirectory: nil, forLocalization: "en"))
        let en = try XCTUnwrap(NSDictionary(contentsOfFile: enPath) as? [String: String])
        let keys = en.keys.filter { $0.hasPrefix("bot.commands.") || $0.hasPrefix("slash.menu.") || $0.hasPrefix("bot.profile.") }
        XCTAssertGreaterThanOrEqual(keys.count, 30)
        for loc in bundle.localizations where loc != "Base" {
            guard let path = bundle.path(forResource: "Localizable", ofType: "strings", inDirectory: nil, forLocalization: loc),
                  let dict = NSDictionary(contentsOfFile: path) as? [String: String] else { continue }
            for key in keys { XCTAssertNotNil(dict[key], "\(loc) is missing \(key)") }
            for key in ["slash.menu.no_commands", "slash.menu.no_match", "bot.commands.header", "bot.commands.empty.body"] {
                XCTAssertTrue(dict[key]?.contains("%@") ?? false, "\(loc) \(key) lost its placeholder")
            }
        }
    }

    private func failure(_ r: Result<[BotTrigger], BotCommandSpec.Problem>) -> BotCommandSpec.Problem? {
        if case .failure(let p) = r { return p }
        return nil
    }
}
