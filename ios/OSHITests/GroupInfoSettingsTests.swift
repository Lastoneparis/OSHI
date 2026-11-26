//
//  GroupInfoSettingsTests.swift
//  OSHITests
//
//  __GROUP_INFO_SETTINGS_2026_11_20__ GROUP_INFO_SETTINGS_SPEC §6 — the unit tests
//  every platform owes: wire format against the shared vectors in
//  docs/fixtures/group_settings_v1/, the receive rule (absent keeps, admin
//  replaces, non-admin and unauthenticated ignored), sticky `dissolved`, the join
//  decision table (§3.3), the reaction / post filters, `description: ""`, the
//  invite-stub fix, and old-client tolerance. Pure functions first, then the real
//  `GroupUpdateReceived` funnel into `MessageGroupManager.shared` for the wiring.
//

import XCTest
@testable import OSHI

final class GroupInfoSettingsTests: XCTestCase {

    // Keys of the vectors.
    private let adminKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    private let memberKey = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8="
    private let strangerKey = "U3RyYW5nZXJLZXlDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQw=="

    // MARK: - Vectors

    private func fixture(_ name: String) throws -> Data {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("docs/fixtures/group_settings_v1/\(name).json")
        return try Data(contentsOf: url)
    }

    private func decode(_ data: Data) throws -> MessageGroup {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601   // what handleIncomingGroupUpdate uses
        return try d.decode(MessageGroup.self, from: data)
    }

    private func vector(_ name: String) throws -> MessageGroup { try decode(try fixture(name)) }

    func testVectorWithSettingsDecodesEverySubkey() throws {
        let g = try vector("definition_with_settings")
        let s = try XCTUnwrap(g.settings)
        XCTAssertEqual(s.visibility, .public)
        XCTAssertEqual(s.handle, "swiss_quant")
        XCTAssertTrue(s.showPreview)
        XCTAssertEqual(s.inviteToken, "q1w2e3r4t5y6u7i8o9p0aA")
        XCTAssertEqual(s.reactions, .some)
        XCTAssertEqual(s.allowedReactions, ["👍", "❤️", "🔥"])
        XCTAssertEqual(s.welcome, "Bienvenue ! Lisez le message épinglé.")
        XCTAssertEqual(s.postPolicy, .admins)
        XCTAssertFalse(s.adminDMs)
        XCTAssertFalse(s.dissolved)
        XCTAssertEqual(g.groupDescription, "Quant research, daily notes.")
        XCTAssertEqual(g.stateVersion, 7)
    }

    func testVectorBadSettingsIsSanitisedNotRejected() throws {
        let g = try vector("definition_bad_settings")
        XCTAssertEqual(g.name, "Swiss Quant", "a bad settings object must not drop the definition (1.0.43 GM:220 lesson)")
        let s = try XCTUnwrap(g.settings)
        XCTAssertEqual(s.visibility, .private, "invalid handle forces private")
        XCTAssertNil(s.handle)
        XCTAssertFalse(s.showPreview, "\"yes\" is not a bool")
        XCTAssertEqual(s.reactions, .all)
        XCTAssertEqual(s.allowedReactions.count, 12)
        XCTAssertEqual(s.welcome.utf16.count, 1000)
        XCTAssertEqual(s.postPolicy, .everyone)
        XCTAssertTrue(s.adminDMs, "1 is not a bool → default true")
        XCTAssertFalse(s.dissolved)
        XCTAssertEqual(s.v, 7, "v parsed best-effort")
    }

    func testVectorWithoutSettingsKeepsOursAndAppliesTheRename() throws {
        let current = try vector("definition_with_settings")
        let incoming = try vector("definition_without_settings")
        XCTAssertNil(incoming.settings)
        let d = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: incoming, sender: adminKey)
        let merged = MessageGroupManager.applyGroupUpdate(current: current, incoming: incoming, decision: d)
        XCTAssertEqual(merged.name, "Swiss Quant (renamed by iOS 1.0.43)")
        XCTAssertEqual(merged.settings, current.settings, "absent ≠ cleared (rule 0.3)")
        XCTAssertEqual(merged.groupDescription, current.groupDescription, "absent description keeps ours")
    }

    /// Encode → the definition other platforms read → decode: same settings, and
    /// the JSON keys/values are exactly the spec's (the bytes Android/Desktop parse).
    func testSettingsRoundTripThroughTheFramedDefinition() throws {
        let g = try vector("definition_with_settings")
        let framed = try XCTUnwrap(GroupWireV2.framedDefinition(g))
        XCTAssertTrue(framed.hasPrefix(GroupWireV2.groupUpdatePrefix))
        let json = String(framed.dropFirst(GroupWireV2.groupUpdatePrefix.count))
        let back = try decode(Data(json.utf8))
        XCTAssertEqual(back.settings, g.settings)

        let obj = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        let s = try XCTUnwrap(obj["settings"] as? [String: Any])
        let vec = try XCTUnwrap(JSONSerialization.jsonObject(with: try fixture("definition_with_settings")) as? [String: Any])
        var expected = try XCTUnwrap(vec["settings"] as? [String: Any])
        // __GROUP_COMMENTS_2026_11_21__ §2.12.1: every subkey is emitted, and this
        // vector predates `comments` (absent ⇒ default false, emitted as false).
        expected["comments"] = false
        // __CHANNEL_WEB_2026_11_26__ same for the web-preview subkeys.
        expected["webPreview"] = false
        expected["webIndexing"] = false
        XCTAssertEqual(NSDictionary(dictionary: s), NSDictionary(dictionary: expected),
                       "emitted settings object must equal the shared vector's")
        XCTAssertEqual(obj["description"] as? String, "Quant research, daily notes.")
    }

    func testGroupWithoutSettingsEmitsNoSettingsKey() throws {
        var g = try vector("definition_without_settings")
        g.settings = nil
        let framed = try XCTUnwrap(GroupWireV2.framedDefinition(g))
        XCTAssertFalse(framed.contains("\"settings\""), "a group nobody configured stays byte-identical to today")
    }

    // MARK: - Receive rule (§2.1)

    private func baseGroup(type: GroupType) throws -> MessageGroup {
        var g = try vector("definition_with_settings")
        g.type = type
        return g
    }

    private func incomingWith(_ current: MessageGroup, _ edit: (inout GroupSettings) -> Void) -> MessageGroup {
        var inc = current
        var s = inc.settings ?? GroupSettings()
        edit(&s)
        inc.settings = s
        inc.stateVersion = (current.stateVersion ?? 0) + 1
        return inc
    }

    private func apply(_ current: MessageGroup, _ incoming: MessageGroup, from sender: String?) -> MessageGroup {
        let d = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: incoming, sender: sender)
        XCTAssertTrue(d.apply)
        return MessageGroupManager.applyGroupUpdate(current: current, incoming: incoming, decision: d)
    }

    func testAdminReplacesSettings() throws {
        let cur = try baseGroup(type: .adminOnly)
        let inc = incomingWith(cur) { $0.postPolicy = .everyone; $0.welcome = "Hi"; $0.reactions = .none }
        let m = apply(cur, inc, from: adminKey)
        XCTAssertEqual(m.settings?.postPolicy, .everyone)
        XCTAssertEqual(m.settings?.welcome, "Hi")
        XCTAssertEqual(m.settings?.reactions, GroupSettings.Reactions.none)
    }

    func testNonAdminCannotChangeAnySettingInAdminOnlyOrCollaborativeGroups() throws {
        for type in [GroupType.adminOnly, .collaborative, .publicGroup] {
            let cur = try baseGroup(type: type)
            let inc = incomingWith(cur) {
                $0.visibility = .private; $0.handle = "hijacked_name"; $0.reactions = .all
                $0.welcome = "pwned"; $0.postPolicy = .everyone; $0.adminDMs = true
                $0.dissolved = true; $0.inviteToken = "AAAAAAAAAAAAAAAAAAAAAA"
            }
            let m = apply(cur, inc, from: memberKey)
            XCTAssertEqual(m.settings, cur.settings, "settings-not-admin in a \(type) group")
            XCTAssertEqual(m.type, cur.type, "a member never changes the type")
        }
    }

    func testUnauthenticatedTransportNeverSetsSettings() throws {
        let cur = try baseGroup(type: .collaborative)
        let inc = incomingWith(cur) { $0.postPolicy = .everyone; $0.dissolved = true }
        let m = apply(cur, inc, from: nil)
        XCTAssertEqual(m.settings, cur.settings)
    }

    func testDissolvedIsSticky() throws {
        var cur = try baseGroup(type: .adminOnly)
        cur.settings?.dissolved = true
        let inc = incomingWith(cur) { $0.dissolved = false; $0.welcome = "back" }
        let m = apply(cur, inc, from: adminKey)
        XCTAssertEqual(m.settings?.dissolved, true, "never goes back to false")
        XCTAssertEqual(m.settings?.welcome, "back", "the rest of the admin's object still applies")
    }

    func testSettingsMergePure() {
        var a = GroupSettings(); a.welcome = "a"
        var b = GroupSettings(); b.welcome = "b"
        XCTAssertEqual(GroupSettingsPolicy.merge(current: a, incoming: nil, fromAdmin: true), a)
        XCTAssertEqual(GroupSettingsPolicy.merge(current: a, incoming: b, fromAdmin: true), b)
        XCTAssertEqual(GroupSettingsPolicy.merge(current: a, incoming: b, fromAdmin: false), a)
        XCTAssertNil(GroupSettingsPolicy.merge(current: nil, incoming: b, fromAdmin: false))
    }

    // MARK: - Description (§2.2)

    func testEmptyDescriptionClearsAbsentKeepsLongTruncated() throws {
        let cur = try baseGroup(type: .adminOnly)
        var inc = cur; inc.groupDescription = ""; inc.stateVersion = 8
        XCTAssertEqual(apply(cur, inc, from: adminKey).groupDescription, "", "\"\" clears")
        inc.groupDescription = nil
        XCTAssertEqual(apply(cur, inc, from: adminKey).groupDescription, cur.groupDescription, "absent keeps")
        inc.groupDescription = String(repeating: "é", count: 700)
        XCTAssertEqual(apply(cur, inc, from: adminKey).groupDescription?.utf16.count, 500)

        // Encoder: a cleared description is EMITTED as "" (so it propagates).
        var g = cur; g.groupDescription = ""
        XCTAssertTrue(try XCTUnwrap(GroupWireV2.framedDefinition(g)).contains("\"description\":\"\""))
    }

    func testMemberOfAdminOnlyGroupCannotChangeDescriptionButOpenGroupMemberCan() throws {
        let cur = try baseGroup(type: .adminOnly)
        var inc = cur; inc.groupDescription = "spam"; inc.stateVersion = 8
        XCTAssertEqual(apply(cur, inc, from: memberKey).groupDescription, cur.groupDescription)
        let open = try baseGroup(type: .collaborative)
        var inc2 = open; inc2.groupDescription = "new"; inc2.stateVersion = 8
        XCTAssertEqual(apply(open, inc2, from: memberKey).groupDescription, "new", "shipped rule: whoever may rename")
    }

    // MARK: - Join decision table (§3.3)

    private func publicGroup(meAdmin: Bool = true) throws -> MessageGroup {
        var g = try vector("definition_with_settings")
        if !meAdmin { g.members = g.members.map { GroupMember(publicKey: $0.publicKey, alias: $0.alias, joinedAt: $0.joinedAt, isAdmin: false) } }
        return g
    }

    private func handleJoin(_ groups: [MessageGroup], sender: String?, requester: String?,
                            handle: String? = "swiss_quant", me: String? = nil, accepted: Int = 0) -> GroupJoinVerdict {
        GroupJoinAuthorizer.authorizeHandleJoin(sender: sender, requester: requester, handle: handle,
                                                groups: groups, me: me ?? adminKey,
                                                acceptsInLastHour: { _ in accepted }).0
    }

    func testHandleJoinDecisionTable() throws {
        let g = try publicGroup()
        XCTAssertEqual(handleJoin([g], sender: nil, requester: strangerKey), .refuse("unauthenticated"))
        XCTAssertEqual(handleJoin([g], sender: strangerKey, requester: memberKey), .refuse("body-names-a-third-party"))
        XCTAssertEqual(handleJoin([g], sender: strangerKey, requester: strangerKey, handle: "other_name"), .refuse("unknown-handle"))
        XCTAssertEqual(handleJoin([g], sender: strangerKey, requester: strangerKey, handle: "SWISS_QUANT"), .accept, "handle is case-insensitive")
        XCTAssertEqual(handleJoin([g], sender: strangerKey, requester: strangerKey, me: memberKey), .refuse("not-admin"))
        var dissolved = g; dissolved.settings?.dissolved = true
        XCTAssertEqual(handleJoin([dissolved], sender: strangerKey, requester: strangerKey), .refuse("dissolved"))
        var banned = g; banned.evictedMemberKeys = [strangerKey]
        XCTAssertEqual(handleJoin([banned], sender: strangerKey, requester: strangerKey), .refuse("banned"))
        XCTAssertEqual(handleJoin([g], sender: memberKey, requester: memberKey), .alreadyMember)
        XCTAssertEqual(handleJoin([g], sender: strangerKey, requester: strangerKey, accepted: 60), .refuse("rate-limited"))
        XCTAssertEqual(handleJoin([g], sender: strangerKey, requester: strangerKey, accepted: 59), .accept)
        var priv = g; priv.settings?.visibility = .private
        XCTAssertEqual(handleJoin([priv], sender: strangerKey, requester: strangerKey), .refuse("unknown-handle"),
                       "a private group never answers a handle")
    }

    func testSyncRequestDecisionTable() throws {
        var g = try publicGroup()
        let token = try XCTUnwrap(g.settings?.inviteToken)
        func sync(_ group: MessageGroup, _ t: String?, may: Bool = true, sender: String? = nil) -> GroupJoinVerdict {
            GroupJoinAuthorizer.authorizeSyncRequest(sender: sender ?? strangerKey, requester: strangerKey,
                                                     token: t, group: group, mayAddMembers: may)
        }
        XCTAssertEqual(sync(g, token), .accept)
        XCTAssertEqual(sync(g, nil), .refuse("invite-revoked"), "an old link without the reset token")
        XCTAssertEqual(sync(g, "AAAAAAAAAAAAAAAAAAAAAA"), .refuse("invite-revoked"))
        XCTAssertEqual(sync(g, token, may: false), .refuse("invite-revoked"))
        XCTAssertEqual(sync(g, token, sender: memberKey), .refuse("body-names-a-third-party"))
        var legacy = g; legacy.settings?.inviteToken = nil
        XCTAssertEqual(sync(legacy, nil), .accept, "never reset ⇒ legacy rule")
        XCTAssertEqual(sync(legacy, nil, may: false), .refuse("may-not-add"))
        var noSettings = g; noSettings.settings = nil
        XCTAssertEqual(sync(noSettings, nil), .accept)
        var dissolved = g; dissolved.settings?.dissolved = true
        XCTAssertEqual(sync(dissolved, token), .refuse("dissolved"))
        g.evictedMemberKeys = [strangerKey]
        XCTAssertEqual(sync(g, token), .refuse("banned"))
    }

    func testTokensCompareExactly() {
        XCTAssertTrue(GroupSettings.tokensMatch("abc_DEF-1234567890ab", "abc_DEF-1234567890ab"))
        XCTAssertFalse(GroupSettings.tokensMatch("abc_DEF-1234567890ab", "abc_DEF-1234567890aB"))
        XCTAssertFalse(GroupSettings.tokensMatch("x", nil))
        let t = GroupSettings.newInviteToken()
        XCTAssertEqual(t.count, 22)
        XCTAssertEqual(GroupSettings.validInviteToken(t), t)
    }

    // MARK: - Reaction / post filters (§2.3, §2.4)

    func testReactionFilter() throws {
        let g = try vector("definition_with_settings")   // some: 👍 ❤️ 🔥
        XCTAssertTrue(GroupSettingsPolicy.acceptsIncomingReaction(isRemove: false, emoji: "👍", group: g))
        XCTAssertTrue(GroupSettingsPolicy.acceptsIncomingReaction(isRemove: false, emoji: "❤", group: g), "FE0F-insensitive")
        XCTAssertFalse(GroupSettingsPolicy.acceptsIncomingReaction(isRemove: false, emoji: "😂", group: g))
        XCTAssertTrue(GroupSettingsPolicy.acceptsIncomingReaction(isRemove: true, emoji: "😂", group: g), "remove always accepted")
        var none = g; none.settings?.reactions = .none
        XCTAssertFalse(GroupSettingsPolicy.acceptsIncomingReaction(isRemove: false, emoji: "👍", group: none))
        var emptySome = g; emptySome.settings?.allowedReactions = []
        XCTAssertFalse(GroupSettingsPolicy.acceptsIncomingReaction(isRemove: false, emoji: "👍", group: emptySome), "some+[] = none")
        var unset = g; unset.settings = nil
        XCTAssertTrue(GroupSettingsPolicy.acceptsIncomingReaction(isRemove: false, emoji: "🖕", group: unset))
    }

    func testPostPolicyFilterAndComposer() throws {
        let g = try vector("definition_with_settings")   // postPolicy admins
        XCTAssertTrue(GroupSettingsPolicy.mayPost(sender: adminKey, group: g))
        XCTAssertFalse(GroupSettingsPolicy.mayPost(sender: memberKey, group: g))
        XCTAssertTrue(GroupSettingsPolicy.mayPost(sender: "SYSTEM", group: g))
        XCTAssertTrue(GroupSettingsPolicy.mayPost(sender: "bot:x", group: g))
        XCTAssertEqual(GroupSettingsPolicy.composerState(me: memberKey, group: g), .onlyAdmins)
        XCTAssertEqual(GroupSettingsPolicy.composerState(me: adminKey, group: g), .open)
        var everyone = g; everyone.settings?.postPolicy = .everyone
        XCTAssertTrue(GroupSettingsPolicy.mayPost(sender: memberKey, group: everyone))
        var dead = g; dead.settings?.dissolved = true
        XCTAssertEqual(GroupSettingsPolicy.composerState(me: adminKey, group: dead), .dissolved)
    }

    // MARK: - Admin log (§2.8)

    func testAdminLogDiff() throws {
        let old = try vector("definition_with_settings")
        var new = old
        new.name = "SQ"
        new.groupDescription = ""
        new.members.append(GroupMember(publicKey: strangerKey, alias: nil, joinedAt: Date(), isAdmin: false))
        new.members[1].isAdmin = true
        new.settings?.welcome = "x"
        new.settings?.postPolicy = .everyone
        new.settings?.dissolved = true
        let kinds = GroupAdminLog.diff(old: old, new: new, actor: adminKey).map(\.kind)
        XCTAssertEqual(Set(kinds), ["renamed", "description_changed", "member_added", "admin_promoted",
                                    "welcome_changed", "post_policy_changed", "dissolved"])
        var left = old; left.members.removeAll { $0.publicKey == memberKey }
        XCTAssertEqual(GroupAdminLog.diff(old: old, new: left, actor: memberKey).map(\.kind), ["member_left"])
        XCTAssertEqual(GroupAdminLog.diff(old: old, new: left, actor: adminKey).map(\.kind), ["member_removed"])
    }

    func testAdminLogPrunes48hAndCaps500() {
        let now = Date()
        let old = GroupAdminEvent(at: now.addingTimeInterval(-49 * 3600), actor: "a", kind: "renamed", detail: nil)
        let fresh = (0..<600).map { GroupAdminEvent(at: now.addingTimeInterval(-Double($0)), actor: "a", kind: "renamed", detail: "\($0)") }
        let p = GroupAdminLog.pruned([old] + fresh, now: now)
        XCTAssertEqual(p.count, 500)
        XCTAssertFalse(p.contains(old))
    }

    // MARK: - Links and registry (§3.1, §4)

    func testHandleRules() {
        XCTAssertEqual(GroupSettings.normalizedHandle("Swiss_Quant"), "swiss_quant")
        XCTAssertNil(GroupSettings.normalizedHandle("abcd"))
        XCTAssertNil(GroupSettings.normalizedHandle(String(repeating: "a", count: 33)))
        XCTAssertNil(GroupSettings.normalizedHandle("bad-handle"))
        XCTAssertNil(GroupSettings.normalizedHandle("ünïcode_x"))
    }

    func testPublicLinkParsing() {
        func h(_ s: String) -> String? { GroupPublicLink.rawHandle(from: URL(string: s)!) }
        XCTAssertEqual(h("https://oshi-messenger.com/g/swiss_quant"), "swiss_quant")
        XCTAssertEqual(h("https://www.oshi-messenger.org/g/Swiss_Quant"), "Swiss_Quant")
        XCTAssertEqual(h("oshi://g/swiss_quant"), "swiss_quant")
        XCTAssertEqual(h("oshi://group/join?h=swiss_quant"), "swiss_quant")
        XCTAssertNil(h("oshi://group/join?gid=5F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9"))
        XCTAssertNil(h("https://oshi-messenger.com/group?gid=x"))
        XCTAssertNil(h("https://evil.example/g/swiss_quant"))
    }

    /// Cross-platform vector: hex sha256("oshi-group-handle-v1:" + UPPER(gid)).
    func testGroupRefVector() {
        let gid = UUID(uuidString: "5f1e2d3c-4b5a-4978-8695-a4b3c2d1e0f9")!
        XCTAssertEqual(GroupHandleRegistry.groupRef(gid),
                       "4ff63d9a1b2fd469d04b5c38783b63db242039e8dbe9ebb2a07bad40ef730c65")
    }

    func testPutBodyListsAdminsCreatorFirstAndPreviewOnlyWhenOptedIn() throws {
        var g = try vector("definition_with_settings")
        g.members[1].isAdmin = true
        var s = try XCTUnwrap(g.settings)
        s.showPreview = false
        var body = GroupHandleRegistry.putBody(g, settings: s)
        XCTAssertEqual(body["controllers"] as? [String], [adminKey, memberKey])
        XCTAssertEqual(body["joinKeys"] as? [String], [adminKey, memberKey])
        XCTAssertTrue(body["preview"] is NSNull)
        XCTAssertNil(body["groupId"], "the server never learns the group id")
        s.showPreview = true
        body = GroupHandleRegistry.putBody(g, settings: s)
        let p = try XCTUnwrap(body["preview"] as? [String: Any])
        XCTAssertEqual(p["name"] as? String, "Swiss Quant")
        XCTAssertEqual(p["memberCount"] as? Int, 2)
    }

    func testInviteLinkCarriesTokenOnlyAfterReset() throws {
        var g = try vector("definition_without_settings")
        XCTAssertFalse(g.inviteLink(inviter: adminKey).contains("&t="))
        var s = GroupSettings(); s.inviteToken = "q1w2e3r4t5y6u7i8o9p0aA"
        g.settings = s
        XCTAssertTrue(g.inviteLink(inviter: adminKey).hasSuffix("&t=q1w2e3r4t5y6u7i8o9p0aA"))
        let req = try XCTUnwrap(GroupWireV2.memberSyncRequest(groupId: g.id, requester: memberKey, inviteToken: "q1w2e3r4t5y6u7i8o9p0aA"))
        XCTAssertTrue(req.contains("\"inviteToken\":\"q1w2e3r4t5y6u7i8o9p0aA\""))
        XCTAssertFalse(try XCTUnwrap(GroupWireV2.memberSyncRequest(groupId: g.id, requester: memberKey)).contains("inviteToken"),
                       "no token ⇒ byte-identical legacy request")
    }

    // MARK: - Invite stub (audit fix)

    func testInviteStubNeverMakesTheUnauthenticatedInviterAnAdmin() {
        let stub = MessageGroupManager.inviteStub(gid: UUID(), inviterKey: strangerKey, name: "x", myKey: memberKey)
        XCTAssertFalse(stub.members.contains { $0.isAdmin }, "the URL's inviter is not authenticated")
        XCTAssertEqual(stub.type, .adminOnly, "nobody edits a pending stub")
        XCTAssertTrue(stub.members.contains { $0.publicKey == strangerKey })
    }
}

// MARK: - Wiring through the real funnel

@MainActor
final class GroupInfoSettingsWiringTests: XCTestCase {
    private let adminKey  = "U2V0dGluZ3NBZG1pbktleUFBQUFBQUFBQUFBQUFBQUFBQQ=="
    private let memberKey = "U2V0dGluZ3NNZW1iZXJLZXlCQkJCQkJCQkJCQkJCQkJCQg=="
    private let myKey     = "U2V0dGluZ3NTZWxmS2V5Q0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0M="
    private let groupId = UUID(uuidString: "6E5E7700-0000-4000-8000-00000000A0A0")!
    private var savedPublicKey: String?

    override func setUp() async throws {
        try await super.setUp()
        savedPublicKey = UserDefaults.standard.string(forKey: "publicKey")
        UserDefaults.standard.set(myKey, forKey: "publicKey")
        MessageGroupManager.shared.groups.removeAll { $0.id == groupId }
        GroupLocalFlags.shared.forgetGroup(groupId)
    }

    override func tearDown() async throws {
        MessageGroupManager.shared.groups.removeAll { $0.id == groupId }
        MessageGroupManager.shared.groupMessages.removeValue(forKey: groupId)
        GroupLocalFlags.shared.forgetGroup(groupId)
        if let savedPublicKey { UserDefaults.standard.set(savedPublicKey, forKey: "publicKey") }
        else { UserDefaults.standard.removeObject(forKey: "publicKey") }
        try await super.tearDown()
    }

    private func group(type: GroupType = .collaborative, version: Int = 1) -> MessageGroup {
        var g = MessageGroup(id: groupId, name: "Canal", type: type, adminPublicKey: adminKey,
                             members: [
                                GroupMember(publicKey: adminKey, alias: "A", joinedAt: Date(timeIntervalSince1970: 0), isAdmin: true),
                                GroupMember(publicKey: memberKey, alias: "M", joinedAt: Date(timeIntervalSince1970: 0), isAdmin: false),
                                GroupMember(publicKey: myKey, alias: "Me", joinedAt: Date(timeIntervalSince1970: 0), isAdmin: false)
                             ],
                             createdAt: Date(timeIntervalSince1970: 0), lastActivity: Date(timeIntervalSince1970: 0))
        g.stateVersion = version
        return g
    }

    private var stored: MessageGroup? { MessageGroupManager.shared.groups.first { $0.id == groupId } }

    private func deliver(_ g: MessageGroup, from sender: String?) throws {
        let enc = JSONEncoder(); enc.dateEncodingStrategy = .iso8601
        var info: [String: Any] = ["groupData": try enc.encode(g)]
        if let sender { info["updatedBy"] = sender }
        NotificationCenter.default.post(name: NSNotification.Name("GroupUpdateReceived"), object: nil, userInfo: info)
    }

    private func settle(_ cond: @escaping () -> Bool) async {
        let deadline = Date().addingTimeInterval(3)
        while Date() < deadline, !cond() { try? await Task.sleep(nanoseconds: 20_000_000) }
    }

    func testAdminSettingsLandAndMemberSettingsDoNot() async throws {
        MessageGroupManager.shared.groups.append(group())
        var fromMember = group(version: 2)
        fromMember.name = "Canal (member)"
        var s = GroupSettings(); s.postPolicy = .admins
        fromMember.settings = s
        try deliver(fromMember, from: memberKey)
        await settle { self.stored?.name == "Canal (member)" }
        XCTAssertEqual(stored?.name, "Canal (member)", "processed (rename allowed in a collaborative group)")
        XCTAssertNil(stored?.settings, "settings-not-admin")

        var fromAdmin = group(version: 3)
        fromAdmin.settings = s
        try deliver(fromAdmin, from: adminKey)
        await settle { self.stored?.settings != nil }
        XCTAssertEqual(stored?.settings?.postPolicy, .admins)
    }

    func testPendingInviteStubIsReplacedByTheRealAdminsDefinition() async throws {
        // The link named a forger as inviter; the real admin answers.
        let forger = "Rm9yZ2VyS2V5RkZGRkZGRkZGRkZGRkZGRkZGRkZGRkY="
        let stub = MessageGroupManager.inviteStub(gid: groupId, inviterKey: forger, name: "?", myKey: myKey)
        MessageGroupManager.shared.groups.append(stub)
        GroupLocalFlags.shared.setPendingStub(groupId, true)

        var real = group(type: .adminOnly, version: 5)
        var s = GroupSettings(); s.welcome = "Bienvenue"
        real.settings = s
        try deliver(real, from: adminKey)
        await settle { self.stored?.name == "Canal" }
        XCTAssertEqual(stored?.name, "Canal")
        XCTAssertEqual(stored?.members.first { $0.isAdmin }?.publicKey, adminKey)
        XCTAssertFalse(stored?.members.contains { $0.publicKey == forger } ?? true)
        XCTAssertFalse(GroupLocalFlags.shared.isPendingStub(groupId))
        XCTAssertTrue(MessageGroupManager.shared.groupMessages[groupId]?.contains {
            $0.systemMessageType == .groupInfo && ($0.plaintextContent ?? "").contains("Bienvenue")
        } ?? false, "welcome row shown once on join")
    }

    func testStubIsNotTakenFromASenderOutsideTheRoster() async throws {
        let stub = MessageGroupManager.inviteStub(gid: groupId, inviterKey: adminKey, name: "?", myKey: myKey)
        MessageGroupManager.shared.groups.append(stub)
        GroupLocalFlags.shared.setPendingStub(groupId, true)
        let outsider = "T3V0c2lkZXJLZXlPT09PT09PT09PT09PT09PT09PT09PTw=="
        let fake = group(version: 5)   // roster without `outsider`
        try deliver(fake, from: outsider)
        try? await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertEqual(stored?.name, "?", "a definition whose sender is not in its own roster never replaces the stub")
        XCTAssertTrue(GroupLocalFlags.shared.isPendingStub(groupId))
    }
}
