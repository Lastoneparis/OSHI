//
//  BotPlatformTests.swift
//  OSHITests — __BOTS_PHASE1_2026_11_24__
//
//  Usernames, links, the HTTP client of the phase-1 routes (over a fake
//  transport: the real request-building and error-mapping code runs), the
//  group command router + directory (over a fake service), bot post
//  edit/delete parsing, and the `<arg>` picker rule.
//

import XCTest
@testable import OSHI

final class BotPlatformTests: XCTestCase {

    // MARK: Fakes

    /// Records every request; answers from a queue (default 200 `{success:true}`).
    final class FakeTransport {
        var requests: [URLRequest] = []
        var answers: [(Int, String)] = []
        func callAsFunction(_ r: URLRequest) async throws -> (Data, URLResponse) {
            requests.append(r)
            let (status, body) = answers.isEmpty ? (200, #"{"success":true}"#) : answers.removeFirst()
            let resp = HTTPURLResponse(url: r.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
            return (Data(body.utf8), resp)
        }
        func json(_ i: Int) -> [String: Any] {
            (try? JSONSerialization.jsonObject(with: requests[i].httpBody ?? Data())) as? [String: Any] ?? [:]
        }
    }

    final class Counter { var n = 0 }

    private func service(_ t: FakeTransport, signed: Counter? = nil) -> HTTPBotPlatformService {
        var s = HTTPBotPlatformService()
        s.baseURL = "https://example.test"
        s.transport = { try await t($0) }
        s.signer = { r in
            r.setValue("sig", forHTTPHeaderField: "x-oshi-signature")
            signed?.n += 1
            return true
        }
        return s
    }

    final class FakePlatform: BotPlatformService {
        var dispatched: [(String, String, String, String)] = []
        var commands: [String: [BotCommandSpec]] = [:]
        var dispatchExpectation: XCTestExpectation?
        func profile(username: String) async throws -> BotPublicProfile { throw BotPlatformError.notFound }
        func search(_ query: String) async throws -> [BotPublicProfile] { [] }
        func setProfile(token: String, update: BotProfileUpdate) async throws -> BotPublicProfile? { nil }
        func revokeToken(_ token: String) async throws -> (token: String, botId: String?) { (token + "-new", nil) }
        func usernameUnavailableReason(_ username: String, token: String?) async throws -> BotPlatformError? { nil }
        func removeBot(botId: String, groupId: String, adderPublicKey: String) async throws {}
        func publicCommands(botId: String, userPublicKey: String, groupId: String?) async throws -> [BotCommandSpec] {
            guard let c = commands[botId] else { throw BotPlatformError.notFound }
            return c
        }
        func addBot(_ request: BotAddRequest) async throws {}
        func dispatchCommand(botId: String, groupId: String, text: String, fromPublicKey: String) async throws {
            await MainActor.run {
                dispatched.append((botId, groupId, text, fromPublicKey))
                dispatchExpectation?.fulfill()
            }
        }
    }

    private func bot(_ name: String, type: BotType = .webhook, groups: [UUID] = [],
                     commands: [String] = []) -> OSHIBot {
        OSHIBot(id: UUID(), token: "tok-\(name)", name: name, username: name.lowercased() + "bot",
                description: "", avatarEmoji: "🎲", type: type, isEnabled: true,
                permissions: BotPermissions(),
                triggers: commands.map { BotTrigger(id: UUID(), type: .command, condition: "/" + $0,
                                                    responseTemplate: "", isActive: true) },
                webhookURL: nil, scriptContent: nil, assignedGroups: groups, createdAt: Date(),
                lastActivity: nil, messagesSent: 0, ownerPublicKey: "k")
    }

    // MARK: Username

    func testUsernameRules() {
        XCTAssertNil(BotUsername.problem("weatherbot"))
        XCTAssertNil(BotUsername.problem("@Weather_Bot"))           // normalised
        XCTAssertEqual(BotUsername.problem(""), .empty)
        XCTAssertEqual(BotUsername.problem("abbot"), .tooShort)      // 5 chars: the regex needs 6
        XCTAssertEqual(BotUsername.problem("weather"), .mustEndWithBot)
        XCTAssertEqual(BotUsername.problem("1weatherbot"), .mustStartWithLetter)
        XCTAssertEqual(BotUsername.problem("_weatherbot"), .mustStartWithLetter)
        XCTAssertEqual(BotUsername.problem("météobot"), .invalidCharacters)
        XCTAssertEqual(BotUsername.problem(String(repeating: "a", count: 30) + "bot"), .tooLong)
        XCTAssertNil(BotUsername.problem(String(repeating: "a", count: 29) + "bot"))  // 32
    }

    func testSanitizeWhileTyping() {
        XCTAssertEqual(BotUsername.sanitizeWhileTyping("@Team Polls-Bot!"), "team_polls_bot")
        XCTAssertEqual(BotUsername.sanitizeWhileTyping(String(repeating: "x", count: 50)).count, 32)
    }

    func testSuggestionsAreValidAndSkipTaken() {
        let s = BotUsername.suggestions(forName: "Météo Paris", taken: ["meteo_parisbot"])
        XCTAssertEqual(s.count, 3)
        XCTAssertFalse(s.contains("meteo_parisbot"))
        XCTAssertTrue(s.allSatisfy(BotUsername.isValid), "\(s)")
        XCTAssertEqual(s.first, "meteo_paris_bot")
        XCTAssertTrue(BotUsername.suggestions(forName: "Q").allSatisfy(BotUsername.isValid))
        XCTAssertTrue(BotUsername.suggestions(forName: "🤖🤖").allSatisfy(BotUsername.isValid))
        XCTAssertTrue(BotUsername.suggestions(forName: "Weather Bot").contains("weatherbot"))
    }

    // MARK: Links

    func testUniversalLinks() {
        XCTAssertEqual(BotLink.parse(URL(string: "https://oshi-messenger.com/bot/WeatherBot")!),
                       BotLink(username: "weatherbot", startPayload: nil))
        XCTAssertEqual(BotLink.parse(URL(string: "https://www.oshi-messenger.com/bot/weatherbot?start=abc-1_Z")!),
                       BotLink(username: "weatherbot", startPayload: "abc-1_Z"))
        XCTAssertEqual(BotLink.parse(URL(string: "https://oshi-messenger.com/bot/weatherbot?start=a%20b")!)?.startPayload, nil)
        XCTAssertEqual(BotLink.parse(URL(string: "oshi://bot/weatherbot?start=x")!),
                       BotLink(username: "weatherbot", startPayload: "x"))
        // Not bot links: routing must fall through to the existing routes.
        XCTAssertNil(BotLink.parse(URL(string: "https://oshi-messenger.com/group/1234")!))
        XCTAssertNil(BotLink.parse(URL(string: "https://oshi-messenger.com/add?key=abc")!))
        XCTAssertNil(BotLink.parse(URL(string: "oshi://group/join?gid=1")!))
        XCTAssertNil(BotLink.parse(URL(string: "https://evil.example/bot/weatherbot")!))
        // A bot link with a bad name still parses — the sheet says "doesn't exist".
        XCTAssertEqual(BotLink.parse(URL(string: "https://oshi-messenger.com/bot/")!)?.username, "")
        XCTAssertEqual(BotLink.url(username: "@WeatherBot")?.absoluteString, "https://oshi-messenger.com/bot/weatherbot")
    }

    func testPublicIdMatchesServer() {
        // python: sha256(b"oshi-bot-id:abc").hexdigest()[:16]
        XCTAssertEqual(BotPublicID.forToken("abc"), "304de9db79b657ab")
        XCTAssertEqual(BotPublicID.localUUID(for: "x"), BotPublicID.localUUID(for: "x"))
        XCTAssertNotEqual(BotPublicID.localUUID(for: "x"), BotPublicID.localUUID(for: "y"))
    }

    // MARK: HTTP client

    func testProfileRequestAndParse() async throws {
        let t = FakeTransport()
        t.answers = [(200, #"{"success":true,"bot":{"botId":"b1","username":"weatherbot","name":"Weather","about":"Forecasts","description":"d","avatarEmoji":"🌦️","commands":[{"command":"/Today","description":"Today"},{"command":"bad cmd"}],"verified":false,"hosting":"remote","public":true}}"#)]
        let p = try await service(t).profile(username: "@WeatherBot")
        XCTAssertEqual(t.requests[0].url?.absoluteString, "https://example.test/api/bot/public?username=weatherbot")
        XCTAssertEqual(p.botId, "b1")
        XCTAssertEqual(p.commands.map(\.name), ["today"])
        XCTAssertTrue(p.isPublic)
        XCTAssertTrue(p.isRemote)
    }

    func testProfileNotFoundAndInvalidNameNeverHitsNetwork() async {
        let t = FakeTransport()
        t.answers = [(404, #"{"success":false,"error":"not_found"}"#)]
        do { _ = try await service(t).profile(username: "ghostbot"); XCTFail() }
        catch { XCTAssertEqual(error as? BotPlatformError, .notFound) }
        do { _ = try await service(t).profile(username: "no"); XCTFail() }
        catch { XCTAssertEqual(error as? BotPlatformError, .notFound) }
        XCTAssertEqual(t.requests.count, 1)
    }

    func testSetProfileBodyAndUsernameTaken() async throws {
        let t = FakeTransport()
        t.answers = [(409, #"{"success":false,"error":"username_taken"}"#),
                     (400, #"{"success":false,"error":"username_invalid"}"#),
                     (200, #"{"success":true,"bot":{"botId":"b","username":"mybot","name":"N"}}"#)]
        let s = service(t)
        do { _ = try await s.setProfile(token: "T", update: BotProfileUpdate(username: "@MyBot")); XCTFail() }
        catch { XCTAssertEqual(error as? BotPlatformError, .usernameTaken) }
        do { _ = try await s.setProfile(token: "T", update: BotProfileUpdate(username: "x")); XCTFail() }
        catch { XCTAssertEqual(error as? BotPlatformError, .usernameInvalid) }
        let echoed = try await s.setProfile(token: "T", update: BotProfileUpdate(about: String(repeating: "a", count: 200), isPublic: true))
        XCTAssertEqual(echoed?.username, "mybot")
        XCTAssertEqual(t.requests[0].url?.path, "/api/bot/setMyProfile")
        XCTAssertEqual(t.json(0)["username"] as? String, "mybot")
        XCTAssertEqual(t.json(0)["token"] as? String, "T")
        XCTAssertNil(t.json(0)["name"], "unchanged fields are not sent")
        XCTAssertEqual((t.json(2)["about"] as? String)?.count, 120)
        XCTAssertEqual(t.json(2)["public"] as? Bool, true)
    }

    func testSearchStripsAtAndSkipsEmpty() async throws {
        let t = FakeTransport()
        t.answers = [(200, #"{"success":true,"bots":[{"botId":"a","username":"abot1bot","name":"A"},{"nope":1}]}"#)]
        let s = service(t)
        let found = try await s.search("@wea")
        XCTAssertEqual(found.map(\.botId), ["a"])
        XCTAssertEqual(t.requests[0].url?.query, "q=wea")
        let none = try await s.search("  @ ")
        XCTAssertTrue(none.isEmpty)
        XCTAssertEqual(t.requests.count, 1)
    }

    func testDispatchCommandShapeIsSigned() async throws {
        let t = FakeTransport()
        let signed = Counter()
        try await service(t, signed: signed).dispatchCommand(botId: "b1", groupId: "G", text: "/poll x", fromPublicKey: "pk")
        XCTAssertEqual(signed.n, 1)
        XCTAssertEqual(t.requests[0].httpMethod, "POST")
        XCTAssertEqual(t.requests[0].url?.path, "/api/bot/dispatch")
        XCTAssertEqual(t.requests[0].value(forHTTPHeaderField: "x-oshi-signature"), "sig")
        let body = t.json(0)
        XCTAssertEqual(body["kind"] as? String, "command")
        XCTAssertEqual(body["botId"] as? String, "b1")
        XCTAssertEqual(body["groupId"] as? String, "G")
        XCTAssertEqual(body["text"] as? String, "/poll x")
        XCTAssertEqual(body["fromPublicKey"] as? String, "pk")
    }

    func testCommandsQueryEncodesPlusAndOptionalGroup() async throws {
        let t = FakeTransport()
        t.answers = [(200, #"{"success":true,"commands":[{"command":"poll","description":"Poll <q>"}]}"#),
                     (403, #"{"success":false,"error":"not_member"}"#)]
        let s = service(t)
        let list = try await s.publicCommands(botId: "b", userPublicKey: "a+b/c=", groupId: "G")
        XCTAssertEqual(list.map(\.name), ["poll"])
        XCTAssertTrue(t.requests[0].url!.absoluteString.contains("userPublicKey=a%2Bb/c%3D") ||
                      t.requests[0].url!.absoluteString.contains("userPublicKey=a%2Bb%2Fc%3D"),
                      t.requests[0].url!.absoluteString)
        XCTAssertTrue(t.requests[0].url!.absoluteString.contains("groupId=G"))
        do { _ = try await s.publicCommands(botId: "b", userPublicKey: "k", groupId: nil); XCTFail() }
        catch { XCTAssertEqual(error as? BotPlatformError, .notMember) }
        XCTAssertFalse(t.requests[1].url!.absoluteString.contains("groupId"))
    }

    func testAddBotCarriesPermissions() async throws {
        let t = FakeTransport()
        var p = BotGroupPermissions()
        p.canPin = true
        let signed = Counter()
        try await service(t, signed: signed).addBot(BotAddRequest(botId: "b", groupId: "G", kind: "channel",
                                                                  members: ["m1", "me"], permissions: p, adderPublicKey: "me"))
        let body = t.json(0)
        XCTAssertEqual(signed.n, 1, "addToGroup must be signed (S4)")
        XCTAssertEqual(t.requests[0].url?.path, "/api/bot/addToGroup")
        XCTAssertEqual(body["kind"] as? String, "channel")
        XCTAssertEqual(body["adderPublicKey"] as? String, "me")
        XCTAssertNil(body["name"], "the server does not need the group name")
        XCTAssertEqual(body["members"] as? [String], ["m1", "me"])
        XCTAssertEqual(body["permissions"] as? [String: Bool],
                       ["canPost": true, "canEdit": true, "canDelete": false, "canPin": true])
    }

    func testRevokeTokenNeedsAFreshToken() async throws {
        let t = FakeTransport()
        t.answers = [(200, #"{"success":true,"token":"NEW","botId":"ABC"}"#), (200, #"{"success":true,"token":"OLD"}"#)]
        let s = service(t)
        let fresh = try await s.revokeToken("OLD")
        XCTAssertEqual(fresh.token, "NEW")
        XCTAssertEqual(fresh.botId, "abc")
        do { _ = try await s.revokeToken("OLD"); XCTFail() }
        catch { XCTAssertEqual(error as? BotPlatformError, .malformed) }
    }

    func testUsernameAvailability() async throws {
        let t = FakeTransport()
        t.answers = [(200, #"{"success":true,"available":true}"#),
                     (200, #"{"success":true,"available":false,"reason":"taken"}"#),
                     (200, #"{"success":true,"available":false,"reason":"invalid"}"#)]
        let s = service(t)
        let free = try await s.usernameUnavailableReason("@Teambot", token: "T")
        XCTAssertNil(free)
        XCTAssertEqual(t.requests[0].url?.path, "/api/bot/usernameAvailable")
        XCTAssertTrue(t.requests[0].url!.query!.contains("username=teambot"))
        XCTAssertTrue(t.requests[0].url!.query!.contains("token=T"))
        let taken = try await s.usernameUnavailableReason("teambot", token: nil)
        XCTAssertEqual(taken, .usernameTaken)
        let invalid = try await s.usernameUnavailableReason("teambot", token: nil)
        XCTAssertEqual(invalid, .usernameInvalid)
        let local = try await s.usernameUnavailableReason("nope", token: nil)
        XCTAssertEqual(local, .usernameInvalid)
        XCTAssertEqual(t.requests.count, 3, "an invalid name never reaches the network")
    }

    func testRemoveFromGroupIsSigned() async throws {
        let t = FakeTransport()
        let signed = Counter()
        try await service(t, signed: signed).removeBot(botId: "b", groupId: "G", adderPublicKey: "me")
        XCTAssertEqual(t.requests[0].url?.path, "/api/bot/removeFromGroup")
        XCTAssertEqual(signed.n, 1)
        XCTAssertEqual(t.json(0)["adderPublicKey"] as? String, "me")
    }

    func testStableBotIdSurvivesTokenChange() {
        var b = bot("Stable")
        let original = b.publicId
        XCTAssertEqual(original, BotPublicID.forToken("tok-Stable"))
        b.publicBotId = original
        b.token = "fresh"
        XCTAssertEqual(b.publicId, original, "never re-derived from a new token (S3)")
    }

    func testSigned401IsResignedOnceAnd403NeverLoops() async throws {
        let t = FakeTransport()
        let signed = Counter()
        t.answers = [(401, #"{"success":false,"error":"signature_required"}"#), (200, #"{"success":true}"#)]
        try await service(t, signed: signed).dispatchCommand(botId: "b", groupId: "G", text: "/x", fromPublicKey: "pk")
        XCTAssertEqual(t.requests.count, 2)
        XCTAssertEqual(signed.n, 2)

        let t2 = FakeTransport()
        t2.answers = [(401, "{}"), (401, "{}")]
        do { try await service(t2).dispatchCommand(botId: "b", groupId: "G", text: "/x", fromPublicKey: "pk"); XCTFail() }
        catch { XCTAssertEqual(error as? BotPlatformError, .signatureRequired) }
        XCTAssertEqual(t2.requests.count, 2, "retried ONCE")

        let t3 = FakeTransport()
        t3.answers = [(403, #"{"success":false,"error":"forbidden"}"#)]
        do { try await service(t3).dispatchCommand(botId: "b", groupId: "G", text: "/x", fromPublicKey: "pk"); XCTFail() }
        catch { XCTAssertEqual(error as? BotPlatformError, .forbidden) }
        XCTAssertEqual(t3.requests.count, 1)

        // Unsigned owner routes are not retried.
        let t4 = FakeTransport()
        t4.answers = [(401, "{}")]
        _ = try? await service(t4).setProfile(token: "T", update: BotProfileUpdate(about: "a"))
        XCTAssertEqual(t4.requests.count, 1)
    }

    @MainActor
    func testButtonTapAndMiniAppSessionAreSignedWithOneRetry() async throws {
        let t = FakeTransport()
        t.answers = [(401, "{}"), (200, #"{"success":true}"#)]
        var signs = 0
        let r = URLRequest(url: URL(string: "https://example.test/api/bot/dispatch")!)
        let (_, resp) = try await BotManager.signedSend(r, transport: { try await t($0) },
                                                        sign: { $0.setValue("s", forHTTPHeaderField: "x-oshi-signature"); signs += 1; return true })
        XCTAssertEqual((resp as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertEqual(signs, 2)
        XCTAssertEqual(t.requests.map { $0.value(forHTTPHeaderField: "x-oshi-signature") }, ["s", "s"])
        let src = try String(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("OSHI/BotManager.swift"), encoding: .utf8)
        XCTAssertEqual(src.components(separatedBy: "try await signedSend(request)").count - 1, 2,
                       "dispatch AND miniapp/session go through the signer")
    }

    func testSuccessFalseWith200IsAnError() {
        XCTAssertEqual(HTTPBotPlatformService.error(status: 200, data: Data(#"{"success":false,"error":"rate_limited"}"#.utf8)), .rateLimited)
        XCTAssertNil(HTTPBotPlatformService.error(status: 200, data: Data(#"{"success":true}"#.utf8)))
        XCTAssertEqual(HTTPBotPlatformService.error(status: 401, data: Data()), .signatureRequired)
        XCTAssertEqual(HTTPBotPlatformService.error(status: 500, data: Data()), .http(500))
    }

    // MARK: Group commands

    func testRouterParse() {
        XCTAssertEqual(BotGroupCommandRouter.parse("/Poll Lunch?\nsecond line"),
                       .init(command: "poll", username: nil, line: "/Poll Lunch?"))
        XCTAssertEqual(BotGroupCommandRouter.parse("/poll@TeamBot x")?.username, "teambot")
        XCTAssertNil(BotGroupCommandRouter.parse("hello /poll"))
        XCTAssertNil(BotGroupCommandRouter.parse("/"))
        XCTAssertNil(BotGroupCommandRouter.parse("/poll@"))
        XCTAssertNil(BotGroupCommandRouter.parse("/pöll"))
    }

    func testRouterTargets() {
        let a = GroupRemoteBot(botId: "a", name: "A", username: "abot1bot",
                               commands: [BotCommandSpec(name: "poll", description: "")])
        let b = GroupRemoteBot(botId: "b", name: "B", username: "bbot1bot",
                               commands: [BotCommandSpec(name: "poll", description: ""), BotCommandSpec(name: "help", description: "")])
        XCTAssertEqual(BotGroupCommandRouter.targets(for: "/poll", bots: [a, b]), ["a", "b"])
        XCTAssertEqual(BotGroupCommandRouter.targets(for: "/help me", bots: [a, b]), ["b"])
        XCTAssertEqual(BotGroupCommandRouter.targets(for: "/poll@bbot1bot", bots: [a, b]), ["b"])
        XCTAssertEqual(BotGroupCommandRouter.targets(for: "/other@abot1bot", bots: [a, b]), ["a"])
        XCTAssertEqual(BotGroupCommandRouter.targets(for: "/poll@nobodybot", bots: [a, b]), [])
        XCTAssertEqual(BotGroupCommandRouter.targets(for: "just text", bots: [a, b]), [])
    }

    @MainActor
    func testDirectoryDispatchesToRemoteBotsOnly() async {
        let defaults = UserDefaults(suiteName: "BotPlatformTests-\(UUID())")!
        let dir = GroupBotDirectory(defaults: defaults)
        let fake = FakePlatform()
        dir.service = fake
        dir.callerPublicKey = { "me" }
        let g = UUID()
        dir.note(botId: "R1", name: "Remote", groupId: g)
        fake.commands["r1"] = [BotCommandSpec(name: "poll", description: "Start a poll <question>")]
        await dir.refreshCommands(in: g)
        XCTAssertEqual(dir.bots(in: g).first?.commands.map(\.name), ["poll"])

        let mineRemote = bot("Mine", type: .webhook, groups: [g], commands: ["poll"])
        let mineLocal = bot("Local", type: .automation, groups: [g], commands: ["poll"])
        XCTAssertEqual(Set(dir.routableBots(in: g, owned: [mineRemote, mineLocal]).map(\.botId)),
                       ["r1", BotPublicID.forToken(mineRemote.token)], "a local bot is never dispatched to")

        let exp = expectation(description: "two dispatches")
        exp.expectedFulfillmentCount = 2
        fake.dispatchExpectation = exp
        let targets = dir.dispatchIfCommand("/poll Lunch?\nthe rest stays here", groupId: g, owned: [mineRemote, mineLocal])
        XCTAssertEqual(targets.count, 2)
        await fulfillment(of: [exp], timeout: 2)
        XCTAssertTrue(fake.dispatched.allSatisfy { $0.2 == "/poll Lunch?" && $0.1 == g.uuidString && $0.3 == "me" })
        XCTAssertTrue(dir.dispatchIfCommand("no command", groupId: g, owned: []).isEmpty)

        // Picker rows for the directory bot: `<arg>` inserts instead of sending.
        let rows = dir.slashCommands(in: g, owned: [mineRemote])
        XCTAssertEqual(rows.map(\.command), ["/poll"])
        XCTAssertFalse(rows[0].sendsOnPick)

        // A bot the server no longer knows leaves the directory.
        dir.note(botId: "gone", name: "G", groupId: g)
        await dir.refreshCommands(in: g)
        XCTAssertFalse(dir.bots(in: g).contains { $0.botId == "gone" })

        // Persisted.
        XCTAssertEqual(GroupBotDirectory(defaults: defaults).bots(in: g).map(\.botId), ["r1"])
    }

    // MARK: Picker rule

    func testArgumentCommandsInsert() {
        XCTAssertTrue(SlashCommands.takesArguments("Set a reminder: /remind <time> <text>"))
        XCTAssertFalse(SlashCommands.takesArguments("Show the rules"))
        XCTAssertFalse(SlashCommands.takesArguments("a <> b"))
        XCTAssertFalse(SlashCommands.takesArguments("1 < 2"))
        var b = bot("Arg", type: .automation, commands: [])
        b.triggers = [BotTrigger(id: UUID(), type: .command, condition: "/remind", responseTemplate: "",
                                 isActive: true, commandDescription: "Remind me <time>"),
                      BotTrigger(id: UUID(), type: .command, condition: "/rules", responseTemplate: "",
                                 isActive: true, commandDescription: "Rules")]
        let cmds = SlashCommands.commands(from: [b])
        XCTAssertEqual(cmds.first { $0.command == "/remind" }?.sendsOnPick, false)
        XCTAssertEqual(cmds.first { $0.command == "/rules" }?.sendsOnPick, true)
    }

    func testRemoteCommandsAloneOpenTheMenu() {
        let extra = [SlashCommand(command: "/poll", description: "", botName: "R", botEmoji: "🤖",
                                  botID: UUID(), sendsOnPick: true)]
        let menu = SlashMenuContent.make(for: "/", bots: [], extra: extra)
        XCTAssertTrue(menu?.commands.contains { $0.command == "/poll" } == true)
        XCTAssertNil(menu?.hint)
        XCTAssertEqual(SlashMenuContent.make(for: "/zz", bots: [], extra: extra)?.hint, .noMatch(query: "zz"))
        XCTAssertNil(SlashMenuContent.make(for: "/zz", bots: [], extra: []))
    }

    // MARK: Bot post actions (S5)

    func testPostActionParse() {
        let g = UUID()
        let base: [String: Any] = ["type": "bot_message_action", "groupId": g.uuidString, "messageId": "env1",
                                   "targetMessageId": "m1", "botId": "B1"]
        var edit = base
        edit["action"] = "edit"
        edit["content"] = "Votes: 3"
        edit["replyMarkup"] = ["inline_keyboard": [[["text": "Yes", "callback_data": "y"]]]]
        let e = BotPostUpdate.parse(edit, outerBotId: "b1")
        XCTAssertEqual(e?.groupId, g)
        XCTAssertEqual(e?.messageId, "m1", "the TARGET is what changes, not the action envelope")
        XCTAssertEqual(e?.botId, "b1")
        if case .edit(let text, let kb)? = e?.kind {
            XCTAssertEqual(text, "Votes: 3")
            XCTAssertEqual(kb??.rows.first?.first?.text, "Yes")
        } else { XCTFail() }

        // replyMarkup: null → remove the keyboard; key absent → unchanged.
        var strip = base
        strip["action"] = "edit"
        strip["replyMarkup"] = NSNull()
        if case .edit(nil, let kb)? = BotPostUpdate.parse(strip)?.kind { XCTAssertNotNil(kb); XCTAssertNil(kb!) } else { XCTFail() }
        var textOnly = base
        textOnly["action"] = "edit"
        textOnly["content"] = "x"
        if case .edit("x", let kb)? = BotPostUpdate.parse(textOnly)?.kind { XCTAssertNil(kb) } else { XCTFail() }

        for (action, kind) in [("delete", BotPostUpdate.Kind.delete), ("pin", .pin), ("unpin", .unpin)] {
            var d = base
            d["action"] = action
            XCTAssertEqual(BotPostUpdate.parse(d)?.kind, kind)
        }
        var empty = base
        empty["action"] = "edit"
        XCTAssertNil(BotPostUpdate.parse(empty), "edit with nothing")
        var other = base
        other["action"] = "delete"
        XCTAssertNil(BotPostUpdate.parse(other, outerBotId: "someoneelse"), "inner botId must be the outer one")
        var noGroup = other
        noGroup["groupId"] = "nope"
        XCTAssertNil(BotPostUpdate.parse(noGroup))
        var wrongType = other
        wrongType["type"] = "bot_message"
        XCTAssertNil(BotPostUpdate.parse(wrongType))
    }

    func testPostLocalIdIsStable() {
        let u = UUID()
        XCTAssertEqual(BotPostUpdate.localId(forServerId: u.uuidString), u)
        XCTAssertEqual(BotPostUpdate.localId(forServerId: "17"), BotPostUpdate.localId(forServerId: "17"))
        XCTAssertNotEqual(BotPostUpdate.localId(forServerId: "17"), BotPostUpdate.localId(forServerId: "18"))
    }

    func testOnlyTheAuthorBotMayChangeItsPost() {
        let u = BotPostUpdate(kind: .delete, groupId: UUID(), messageId: "m", botId: "b1")
        XCTAssertTrue(BotPostUpdate.isAuthor(senderKey: "bot:abcd", recordedBotId: "B1", update: u))
        XCTAssertFalse(BotPostUpdate.isAuthor(senderKey: "bot:abcd", recordedBotId: "b2", update: u))
        XCTAssertFalse(BotPostUpdate.isAuthor(senderKey: "bot:abcd", recordedBotId: nil, update: u), "unknown author → drop")
        XCTAssertFalse(BotPostUpdate.isAuthor(senderKey: "PERSONKEY", recordedBotId: "b1", update: u),
                       "a member's message is never a bot's to edit")
    }

    func testPostAuthorsRecordIsBounded() {
        let d = UserDefaults(suiteName: "BotPostAuthors-\(UUID())")!
        let first = UUID()
        BotPostAuthors.record(botId: "B1", localId: first, defaults: d)
        XCTAssertEqual(BotPostAuthors.botId(for: first, defaults: d), "b1")
        for _ in 0..<5 { BotPostAuthors.record(botId: "b2", localId: UUID(), defaults: d, cap: 5) }
        XCTAssertNil(BotPostAuthors.botId(for: first, defaults: d), "oldest evicted")
    }

    // MARK: Snippets

    func testSnippetsUseTheRealBaseAndToken() {
        let c = BotConnectSnippets.curl(token: "TKN", groupId: "G1")
        XCTAssertTrue(c.contains("https://oshi-messenger.com/api/bot/send"))
        XCTAssertTrue(c.contains("\"token\":\"TKN\"") && c.contains("\"groupId\":\"G1\""))
        let p = BotConnectSnippets.python(token: "TKN", groupId: nil)
        XCTAssertTrue(p.contains("TOKEN = \"TKN\"") && p.contains("getUpdates") && p.contains("<group-id>"))
    }

    func testRemoteNoticeTextsAreTheContractOnes() {
        // EN/FR exact texts live in the string tables; the {group|channel}
        // variants must differ only by that word.
        let en = Bundle(for: BotManager.self).path(forResource: "en", ofType: "lproj").flatMap(Bundle.init(path:))
        let g = en?.localizedString(forKey: "bot.add.notice.group", value: nil, table: nil)
        let c = en?.localizedString(forKey: "bot.add.notice.channel", value: nil, table: nil)
        XCTAssertEqual(g, "This bot runs on its owner's server. It will see the commands and button taps in this group, not your conversation.")
        XCTAssertEqual(c, "This bot runs on its owner's server. It will see the commands and button taps in this channel, not your conversation.")
        let fr = Bundle(for: BotManager.self).path(forResource: "fr", ofType: "lproj").flatMap(Bundle.init(path:))
        XCTAssertEqual(fr?.localizedString(forKey: "bot.add.notice.channel", value: nil, table: nil),
                       "Ce bot fonctionne sur le serveur de son propriétaire. Il verra les commandes et les appuis sur les boutons dans ce canal, pas vos conversations.")
    }
}
