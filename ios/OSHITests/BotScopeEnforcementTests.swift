//
//  BotScopeEnforcementTests.swift
//  OSHITests
//
//  __BOT_SCOPES_ENFORCED_2026_08_16__ — étape 3, aux trois niveaux.
//
//  RÈGLE   `authorizeEmit` — pure, horloge passée en paramètre.
//  ÇA MARCHE  the same rules driven through the real managers in the running
//             app: a bot with no write scope posts NOTHING in the group, and a
//             bot past its rate limit stops for real.
//
//  The point of the step is not the rules themselves — it is that they have ONE
//  reader. `canSendMedia`, `canPinMessages` and `canManageMembers` were rendered
//  in the bot settings screen and read by no code at all, for months. A rule with
//  a single reader cannot be forgotten at a call site, because there is only one.
//

import XCTest
@testable import OSHI

// MARK: - Niveau 1 — la règle

final class BotEmitAuthorizationTests: XCTestCase {

    private let t0 = Date(timeIntervalSince1970: 1_700_000_000)

    private func manifest(write: BotWriteScope = .reply,
                          media: Bool = false,
                          repliesPerMinute: Int = 6,
                          maxChars: Int = 2000) -> BotManifest {
        BotManifest(
            id: UUID(), name: "Assistant", handle: "assistant",
            runtime: .local,
            scopes: BotScopes(read: .matched, write: write, media: media, pin: false, members: false),
            triggers: [],
            limits: BotLimits(repliesPerMinute: repliesPerMinute, maxReplyChars: maxChars),
            remote: nil
        )
    }

    func testWriteScopeNoneRefuses() {
        let d = BotManager.authorizeEmit(manifest: manifest(write: .none), content: "salut",
                                         hasMedia: false, recentReplies: [], now: t0)
        XCTAssertFalse(d.allow)
        XCTAssertEqual(d.reason, "write-scope-none")
    }

    func testMediaRefusedUnlessScoped() {
        let denied = BotManager.authorizeEmit(manifest: manifest(media: false), content: "photo",
                                              hasMedia: true, recentReplies: [], now: t0)
        XCTAssertFalse(denied.allow)
        XCTAssertEqual(denied.reason, "media-scope-denied")

        let allowed = BotManager.authorizeEmit(manifest: manifest(media: true), content: "photo",
                                               hasMedia: true, recentReplies: [], now: t0)
        XCTAssertTrue(allowed.allow)
    }

    // MARK: Rate limit — the rule the clock parameter exists for

    func testRateLimitCountsOnlyTheLastMinute() {
        let old = (1...10).map { t0.addingTimeInterval(-61 - Double($0)) }   // hors fenêtre
        let fresh = (1...5).map { t0.addingTimeInterval(-Double($0)) }       // dans la fenêtre

        XCTAssertTrue(BotManager.authorizeEmit(manifest: manifest(repliesPerMinute: 6), content: "x",
                                               hasMedia: false, recentReplies: old + fresh, now: t0).allow,
                      "5 réponses dans la minute, plafond 6 — doit passer")

        let atCap = fresh + [t0.addingTimeInterval(-6)]
        let d = BotManager.authorizeEmit(manifest: manifest(repliesPerMinute: 6), content: "x",
                                         hasMedia: false, recentReplies: atCap, now: t0)
        XCTAssertFalse(d.allow)
        XCTAssertEqual(d.reason, "rate-limited")
    }

    func testWindowSlidesRatherThanResets() {
        let six = (1...6).map { t0.addingTimeInterval(-Double($0)) }
        XCTAssertFalse(BotManager.authorizeEmit(manifest: manifest(), content: "x",
                                                hasMedia: false, recentReplies: six, now: t0).allow)
        // Une minute plus tard, les six sont sortis de la fenêtre.
        XCTAssertTrue(BotManager.authorizeEmit(manifest: manifest(), content: "x",
                                               hasMedia: false, recentReplies: six,
                                               now: t0.addingTimeInterval(61)).allow)
    }

    func testZeroLimitSilencesTheBot() {
        XCTAssertFalse(BotManager.authorizeEmit(manifest: manifest(repliesPerMinute: 0), content: "x",
                                                hasMedia: false, recentReplies: [], now: t0).allow)
    }

    // MARK: Truncation

    /// Trop long = tronqué, pas refusé. Une réponse qui déborde est un gabarit
    /// mal écrit, pas une attaque; la refuser ressemblerait pour l'utilisateur à
    /// un bot cassé — exactement la panne que cette migration élimine.
    func testOverlongReplyIsTruncatedNotRefused() {
        let long = String(repeating: "a", count: 5000)
        let d = BotManager.authorizeEmit(manifest: manifest(maxChars: 100), content: long,
                                         hasMedia: false, recentReplies: [], now: t0)
        XCTAssertTrue(d.allow)
        XCTAssertEqual(d.reason, "truncated")
        XCTAssertEqual(d.content.count, 100)
    }

    func testNormalReplyPassesUntouched() {
        let d = BotManager.authorizeEmit(manifest: manifest(), content: "12 °C au refuge",
                                         hasMedia: false, recentReplies: [], now: t0)
        XCTAssertTrue(d.allow)
        XCTAssertEqual(d.reason, "ok")
        XCTAssertEqual(d.content, "12 °C au refuge")
    }
}

// MARK: - Niveau 3 — que ça marche

@MainActor
final class BotScopeLiveEnforcementTests: XCTestCase {

    private let groupId = UUID(uuidString: "B0743333-4444-5555-6666-777777777777")!
    private let me   = "TW9pU2NvcGVLZXlBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQQ=="
    private let peer = "UGVlclNjb3BlS2V5QkJCQkJCQkJCQkJCQkJCQkJCQkJCQg=="

    private var createdBotIds: [UUID] = []
    private var savedPublicKey: String?

    override func setUp() async throws {
        try await super.setUp()
        savedPublicKey = UserDefaults.standard.string(forKey: "publicKey")
        UserDefaults.standard.set(me, forKey: "publicKey")
        var group = MessageGroup(
            id: groupId, name: "Refuge", type: .collaborative, adminPublicKey: me,
            members: [
                GroupMember(publicKey: me, alias: "Moi", joinedAt: Date(timeIntervalSince1970: 0), isAdmin: true),
                GroupMember(publicKey: peer, alias: "Pair", joinedAt: Date(timeIntervalSince1970: 0), isAdmin: false)
            ],
            createdAt: Date(timeIntervalSince1970: 0), lastActivity: Date(timeIntervalSince1970: 0))
        group.setCurrentUser(me)
        MessageGroupManager.shared.groups.removeAll { $0.id == groupId }
        MessageGroupManager.shared.groups.append(group)
        MessageGroupManager.shared.groupMessages[groupId] = []
    }

    override func tearDown() async throws {
        for id in createdBotIds {
            if let bot = BotManager.shared.bots.first(where: { $0.id == id }) { BotManager.shared.deleteBot(bot) }
        }
        createdBotIds.removeAll()
        MessageGroupManager.shared.groups.removeAll { $0.id == groupId }
        MessageGroupManager.shared.groupMessages[groupId] = nil
        if let savedPublicKey { UserDefaults.standard.set(savedPublicKey, forKey: "publicKey") }
        else { UserDefaults.standard.removeObject(forKey: "publicKey") }
        try await super.tearDown()
    }

    @discardableResult
    private func makeBot(canSend: Bool = true, template: String = "pong") -> OSHIBot {
        var bot = BotManager.shared.createBot(name: "Echo", username: "echo", description: "",
                                              type: .automation, ownerPublicKey: me)
        bot.permissions.canSendMessages = canSend
        bot.triggers = [BotTrigger(id: UUID(), type: .command, condition: "/ping",
                                   responseTemplate: template, isActive: true)]
        BotManager.shared.updateBot(bot)
        BotManager.shared.assignBot(bot.id, toGroup: groupId)
        createdBotIds.append(bot.id)
        return bot
    }

    private func deliver(_ text: String) {
        MessageGroupManager.shared.receiveGroupMessage(GroupMessage(
            id: UUID(), groupId: groupId, senderPublicKey: peer,
            encryptedContent: Data(text.utf8), timestamp: Date(), isRead: false,
            messageChainIndex: 0, plaintextContent: text))
    }

    private var botReplies: [GroupMessage] {
        let mine = Set(createdBotIds.map { "bot:" + $0.uuidString.prefix(8) })
        return (MessageGroupManager.shared.groupMessages[groupId] ?? [])
            .filter { mine.contains($0.senderPublicKey) }
    }

    /// `write: .none` — le bot ne poste RIEN dans le groupe. Pas « rien de
    /// visible »: rien du tout.
    func testBotWithoutWriteScopePostsNothing() async throws {
        makeBot(canSend: false)
        deliver("/ping")
        try? await Task.sleep(nanoseconds: 700_000_000)
        XCTAssertTrue(botReplies.isEmpty)
    }

    /// La limite de débit s'arrête pour de vrai: huit déclenchements, six
    /// réponses. Avant l'étape 3 il n'y avait aucune limite du tout.
    func testRateLimitStopsForReal() async throws {
        makeBot()
        for _ in 0..<8 { deliver("/ping") }
        try? await Task.sleep(nanoseconds: 1_200_000_000)

        XCTAssertEqual(botReplies.count, BotLimits().repliesPerMinute,
                       "attendu \(BotLimits().repliesPerMinute) réponses, obtenu \(botReplies.count)")
    }

    /// Le plafond de longueur s'applique au contenu qui part réellement sur le
    /// fil, pas seulement dans la décision.
    func testOverlongReplyIsTruncatedOnTheWire() async throws {
        makeBot(template: String(repeating: "z", count: 5000))
        deliver("/ping")
        try? await Task.sleep(nanoseconds: 700_000_000)

        let body = try XCTUnwrap(botReplies.first?.plaintextContent)
        // Le corps est préfixé du nom du bot par `emit`, d'où la marge.
        XCTAssertLessThanOrEqual(body.count, BotLimits().maxReplyChars + 32)
        XCTAssertGreaterThan(body.count, 100, "tronqué à zéro serait une panne, pas une limite")
    }

    func testOrdinaryBotStillAnswers() async throws {
        makeBot()
        deliver("/ping")
        try? await Task.sleep(nanoseconds: 700_000_000)
        XCTAssertEqual(botReplies.count, 1, "l'étape 3 ne doit pas faire taire un bot normal")
        XCTAssertTrue(try XCTUnwrap(botReplies.first?.plaintextContent).contains("pong"))
    }
}
