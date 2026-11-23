//
//  BotManifestTests.swift
//  OSHITests
//
//  __BOT_MANIFEST_2026_08_16__ — étape 1 du plan de migration des bots.
//
//  Exercises the REAL `BotManager.manifest(for:)`. The manifest exists to make
//  two properties of a bot legible and enforceable — WHERE it runs, and what it
//  is allowed to see — so the first thing worth asserting is that neither can be
//  faked by the bot's own declaration.
//
//  This step changes no behaviour: nothing is migrated, nothing is written. It
//  gives the later steps (moving the trigger hook out of the views, enforcing
//  the scopes in one funnel) something to enforce.
//

import XCTest
@testable import OSHI

final class BotManifestTests: XCTestCase {

    private func makeBot(type: BotType,
                         webhook: String? = nil,
                         permissions: BotPermissions = BotPermissions()) -> OSHIBot {
        OSHIBot(
            id: UUID(),
            token: "0123456789abcdef0123456789abcdef",
            name: "Météo du refuge",
            username: "meteo",
            description: "",
            avatarEmoji: "🤖",
            type: type,
            isEnabled: true,
            permissions: permissions,
            triggers: [
                BotTrigger(id: UUID(), type: .command, condition: "/meteo",
                           responseTemplate: "…", isActive: true)
            ],
            webhookURL: webhook,
            scriptContent: nil,
            assignedGroups: [],
            createdAt: Date(timeIntervalSince1970: 0),
            lastActivity: nil,
            messagesSent: 0,
            ownerPublicKey: "owner"
        )
    }

    // MARK: - The axis BotType was hiding

    func testWebhookIsTheOnlyRemoteRuntime() {
        XCTAssertEqual(BotManager.manifest(for: makeBot(type: .webhook, webhook: "https://x.test")).runtime, .remote)
        for type in [BotType.automation, .scheduled, .moderator] {
            XCTAssertEqual(BotManager.manifest(for: makeBot(type: type)).runtime, .local,
                           "\(type) answers 'what does it do', not 'where does it run'")
        }
    }

    /// The property the whole design rests on: a bot cannot claim to be sealed.
    /// `confidentiality` is computed from the runtime, and there is no setter.
    func testConfidentialityIsDerivedFromRuntimeNotDeclared() {
        XCTAssertEqual(BotManager.manifest(for: makeBot(type: .automation)).confidentiality, "sealed")
        XCTAssertEqual(BotManager.manifest(for: makeBot(type: .webhook, webhook: "https://x.test")).confidentiality, "forwarded")
    }

    /// Encoding must not leak a `confidentiality` field an author could edit and
    /// re-import. It is derived on read, never carried on the wire.
    func testConfidentialityIsNotEncoded() throws {
        let data = try JSONEncoder().encode(BotManager.manifest(for: makeBot(type: .webhook, webhook: "https://x.test")))
        let json = try XCTUnwrap(String(data: data, encoding: .utf8))
        XCTAssertFalse(json.contains("confidentiality"),
                       "a declared confidentiality is a promise; a derived one is a fact")
    }

    // MARK: - Scopes

    func testReadScopeDefaultsToMatchedNotAll() {
        let m = BotManager.manifest(for: makeBot(type: .automation))
        XCTAssertEqual(m.scopes.read, .matched,
                       "`canReadMessages: true` used to mean 'the whole thread'")
        XCTAssertEqual(m.scopes.write, .reply)
    }

    func testReadingDisabledBecomesNone() {
        var perms = BotPermissions()
        perms.canReadMessages = false
        perms.canSendMessages = false
        let m = BotManager.manifest(for: makeBot(type: .automation, permissions: perms))
        XCTAssertEqual(m.scopes.read, .none)
        XCTAssertEqual(m.scopes.write, .none)
    }

    /// The three toggles that were rendered in the UI and read by nothing become
    /// real scopes, carrying over whatever the user had set.
    func testDeadPermissionsBecomeRealScopes() {
        var perms = BotPermissions()
        perms.canSendMedia = true
        perms.canPinMessages = true
        perms.canManageMembers = true
        let m = BotManager.manifest(for: makeBot(type: .moderator, permissions: perms))
        XCTAssertTrue(m.scopes.media)
        XCTAssertTrue(m.scopes.pin)
        XCTAssertTrue(m.scopes.members)
    }

    // MARK: - The remote spec

    func testRemoteSpecNamesWhatActuallyLeavesTheDevice() throws {
        let m = BotManager.manifest(for: makeBot(type: .webhook, webhook: "https://bots.exemple.fr/meteo"))
        let remote = try XCTUnwrap(m.remote)
        XCTAssertEqual(remote.endpoint, "https://bots.exemple.fr/meteo")
        // Exactly what `sendWebhookEvent` posts today — including the sender's
        // public key, to an arbitrary third-party URL, with no disclosure.
        XCTAssertEqual(remote.forwards, ["matched_text", "group_id", "sender_public_key"])
        XCTAssertEqual(remote.whenOffline, .queue)
    }

    func testLocalBotCarriesNoRemoteSpec() {
        XCTAssertNil(BotManager.manifest(for: makeBot(type: .automation)).remote)
        XCTAssertNil(BotManager.manifest(for: makeBot(type: .scheduled)).remote,
                     "a scheduled bot runs on the device — no endpoint may ride along")
    }

    /// A webhook bot whose URL was never set stays remote, with no spec: the
    /// runtime refuses to run it rather than quietly treating it as local.
    func testWebhookWithoutEndpointStaysRemoteWithoutSpec() {
        let m = BotManager.manifest(for: makeBot(type: .webhook, webhook: nil))
        XCTAssertEqual(m.runtime, .remote)
        XCTAssertNil(m.remote)
        XCTAssertEqual(m.confidentiality, "forwarded")
    }

    // MARK: - Wire

    func testManifestRoundTrips() throws {
        let original = BotManager.manifest(for: makeBot(type: .webhook, webhook: "https://x.test"))
        let decoded = try JSONDecoder().decode(BotManifest.self, from: try JSONEncoder().encode(original))
        XCTAssertEqual(decoded, original)
        XCTAssertEqual(decoded.manifest, 1)
        XCTAssertEqual(decoded.triggers.count, 1)
        XCTAssertEqual(decoded.confidentiality, "forwarded", "derived on read, on both sides")
    }

    /// Derivation is a pure read: two calls on the same bot agree, and the bot
    /// is untouched. Step 1 ships with zero behaviour change by construction.
    func testDerivationIsPureAndRepeatable() {
        let bot = makeBot(type: .moderator)
        XCTAssertEqual(BotManager.manifest(for: bot), BotManager.manifest(for: bot))
        XCTAssertEqual(bot.type, .moderator)
    }
}
