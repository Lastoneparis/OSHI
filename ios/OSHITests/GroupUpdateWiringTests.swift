//
//  GroupUpdateWiringTests.swift
//  OSHITests
//
//  __GROUP_UPDATE_AUTHORIZATION_2026_08_16__ — the half the pure tests cannot reach.
//
//  `GroupUpdateAuthorizationTests` proves the RULES. This file proves the WIRING:
//  that a definition arriving the way a real one arrives — posted as
//  `GroupUpdateReceived` with `updatedBy`, through the live observer, into the
//  shipping `MessageGroupManager.shared`, decoded by the shipping decoder, stored
//  by the shipping merge — actually lands the way the rules say.
//
//  It is the difference that matters here: a correct decision function wired to
//  nothing is exactly the failure mode this whole change exists to fix (three bot
//  permissions were declared, rendered, and read by no one). So this test posts a
//  real notification and reads `MessageGroupManager.shared.groups` afterwards.
//
//  Runs on the SIMULATOR against the real manager. No network: group definitions
//  travel in-process here, and the funnel under test is the same one the encrypted
//  1:1 path posts into.
//

import XCTest
@testable import OSHI

@MainActor
final class GroupUpdateWiringTests: XCTestCase {

    private let adminKey  = "V2lyaW5nQWRtaW5LZXlBQUFBQUFBQUFBQUFBQUFBQUFBQQ=="
    private let memberKey = "V2lyaW5nTWVtYmVyS2V5QkJCQkJCQkJCQkJCQkJCQkJCQg=="
    private let myKey     = "V2lyaW5nU2VsZktleUNDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0M="

    private let groupId = UUID(uuidString: "9A7B6C5D-4E3F-2A1B-0C9D-8E7F6A5B4C3D")!

    private var savedPublicKey: String?

    override func setUp() async throws {
        try await super.setUp()
        savedPublicKey = UserDefaults.standard.string(forKey: "publicKey")
        UserDefaults.standard.set(myKey, forKey: "publicKey")
        MessageGroupManager.shared.groups.removeAll { $0.id == groupId }
    }

    override func tearDown() async throws {
        MessageGroupManager.shared.groups.removeAll { $0.id == groupId }
        if let savedPublicKey {
            UserDefaults.standard.set(savedPublicKey, forKey: "publicKey")
        } else {
            UserDefaults.standard.removeObject(forKey: "publicKey")
        }
        try await super.tearDown()
    }

    // MARK: - Helpers

    private func seedGroup(type: GroupType, name: String = "Refuge", version: Int?) {
        var group = MessageGroup(
            id: groupId,
            name: name,
            type: type,
            adminPublicKey: adminKey,
            members: [
                GroupMember(publicKey: adminKey, alias: "Admin", joinedAt: Date(timeIntervalSince1970: 0), isAdmin: true),
                GroupMember(publicKey: memberKey, alias: "Membre", joinedAt: Date(timeIntervalSince1970: 0), isAdmin: false),
                GroupMember(publicKey: myKey, alias: "Moi", joinedAt: Date(timeIntervalSince1970: 0), isAdmin: false)
            ],
            createdAt: Date(timeIntervalSince1970: 0),
            lastActivity: Date(timeIntervalSince1970: 0)
        )
        group.stateVersion = version
        MessageGroupManager.shared.groups.append(group)
    }

    private func encode(_ group: MessageGroup) throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601   // what broadcastFullGroupUpdate uses
        return try encoder.encode(group)
    }

    /// Post exactly what `MessageManager` posts when a `📢GROUP_UPDATE📢` payload
    /// comes out of the encrypted 1:1 funnel.
    private func deliver(_ group: MessageGroup, from sender: String?) throws {
        var userInfo: [String: Any] = ["groupData": try encode(group)]
        if let sender { userInfo["updatedBy"] = sender }
        NotificationCenter.default.post(
            name: NSNotification.Name("GroupUpdateReceived"),
            object: nil,
            userInfo: userInfo
        )
    }

    /// The observer hops through a `Task { @MainActor in … }`, so the store is not
    /// updated on the same turn of the runloop. Poll rather than sleep a fixed
    /// amount: a fixed sleep is either flaky or slow, and usually both.
    private func settle(until condition: @escaping () -> Bool, timeout: TimeInterval = 3) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return }
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    private var stored: MessageGroup? {
        MessageGroupManager.shared.groups.first { $0.id == groupId }
    }

    private func isAdmin(_ key: String) -> Bool {
        stored?.members.first { $0.publicKey == key }?.isAdmin == true
    }

    // MARK: - The escalation, end to end

    func testForgedPromotionThroughTheRealFunnelIsRefused() async throws {
        seedGroup(type: .collaborative, version: 1)

        var forged = try XCTUnwrap(stored)
        forged.stateVersion = 2
        forged.members = forged.members.map {
            GroupMember(publicKey: $0.publicKey, alias: $0.alias, joinedAt: $0.joinedAt,
                        isAdmin: $0.publicKey == memberKey ? true : $0.isAdmin)
        }
        forged.name = "Refuge (renommé)"

        try deliver(forged, from: memberKey)
        // The rename IS allowed in a collaborative group, so it doubles as proof
        // that the update was processed at all and not merely dropped.
        await settle { self.stored?.name == "Refuge (renommé)" }

        XCTAssertEqual(stored?.name, "Refuge (renommé)", "the update never reached the store — wiring, not rules")
        XCTAssertFalse(isAdmin(memberKey), "a member promoted itself through the real funnel")
        XCTAssertTrue(isAdmin(adminKey), "the real admin was dropped")
    }

    func testAdminPromotionThroughTheRealFunnelIsApplied() async throws {
        seedGroup(type: .collaborative, version: 1)

        var update = try XCTUnwrap(stored)
        update.stateVersion = 2
        update.members = update.members.map {
            GroupMember(publicKey: $0.publicKey, alias: $0.alias, joinedAt: $0.joinedAt,
                        isAdmin: $0.publicKey == memberKey ? true : $0.isAdmin)
        }

        try deliver(update, from: adminKey)
        await settle { self.isAdmin(self.memberKey) }

        XCTAssertTrue(isAdmin(memberKey), "an admin's promotion must survive the funnel")
    }

    func testUpdateFromNonMemberIsIgnoredThroughTheRealFunnel() async throws {
        seedGroup(type: .collaborative, name: "Refuge", version: 1)

        var hijack = try XCTUnwrap(stored)
        hijack.stateVersion = 2
        hijack.name = "Détourné"

        try deliver(hijack, from: "U3RyYW5nZXJLZXlEREREREREREREREREREREREREREREQ==")
        await settle { self.stored?.name == "Détourné" }   // must time out

        XCTAssertEqual(stored?.name, "Refuge", "a non-member renamed the group")
    }

    func testStaleReplayIsIgnoredThroughTheRealFunnel() async throws {
        seedGroup(type: .collaborative, name: "Refuge", version: 7)

        var replay = try XCTUnwrap(stored)
        replay.stateVersion = 3
        replay.name = "Ancien nom"

        try deliver(replay, from: adminKey)
        await settle { self.stored?.name == "Ancien nom" }  // must time out

        XCTAssertEqual(stored?.name, "Refuge", "a replayed older definition overwrote the current state")
    }

    /// __UNAUTHENTICATED_GROUP_UPDATE_2026_11_20__ Mesh and the cloud paths post
    /// no `updatedBy`: anyone who knows the group UUID can send on them. They may
    /// still sync cosmetic fields, but the roster, the name and the admin set
    /// stay as they are.
    func testUnauthenticatedTransportCannotRenameAddOrPromote() async throws {
        seedGroup(type: .collaborative, name: "Refuge", version: 1)

        var update = try XCTUnwrap(stored)
        update.stateVersion = 2
        update.name = "Refuge Nord"
        update.members = update.members.map {
            GroupMember(publicKey: $0.publicKey, alias: $0.alias, joinedAt: $0.joinedAt,
                        isAdmin: $0.publicKey == memberKey ? true : $0.isAdmin)
        } + [GroupMember(publicKey: "outsider-key", alias: nil, joinedAt: Date(), isAdmin: false)]
        let pin = UUID()
        update.pinnedMessageId = pin

        try deliver(update, from: nil)
        await settle { self.stored?.pinnedMessageId == pin }

        XCTAssertEqual(stored?.pinnedMessageId, pin, "cosmetic sync must keep working")
        XCTAssertEqual(stored?.name, "Refuge", "an unauthenticated transport renamed the group")
        XCTAssertFalse(stored?.members.contains { $0.publicKey == "outsider-key" } ?? true,
                       "an unauthenticated transport planted a member")
        XCTAssertFalse(isAdmin(memberKey), "…and it cannot hand out admin")
    }
}

// MARK: - __GROUP_E2E_V2_2026_09_23__ Spec vectors (docs/fixtures/group_e2e_v2/vectors.json)

/// Every platform checks the same golden vectors (docs/GROUP_E2E_V2_SPEC.md §9). Pure: the
/// codec in `GroupWireV2` and the model coders, no manager, no network.
final class GroupE2EV2VectorTests: XCTestCase {

    private static let vectorsURL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("docs/fixtures/group_e2e_v2/vectors.json")

    private func vectors() throws -> [String: Any] {
        let data = try Data(contentsOf: Self.vectorsURL)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func section(_ name: String) throws -> [[String: Any]] {
        try XCTUnwrap(try vectors()[name] as? [[String: Any]], name)
    }

    private func vector(_ section: String, _ name: String) throws -> [String: Any] {
        try XCTUnwrap(try self.section(section).first { $0["name"] as? String == name }, name)
    }

    /// §9 equality: same keys, same values, key order free, numbers within 1e-6.
    private func assertSemanticEqual(_ a: Any?, _ b: Any?, path: String = "$", file: StaticString = #filePath, line: UInt = #line) {
        switch (a, b) {
        case let (x as [String: Any], y as [String: Any]):
            XCTAssertEqual(Set(x.keys), Set(y.keys), "keys at \(path)", file: file, line: line)
            for k in x.keys { assertSemanticEqual(x[k], y[k], path: path + "." + k, file: file, line: line) }
        case let (x as [Any], y as [Any]):
            XCTAssertEqual(x.count, y.count, "count at \(path)", file: file, line: line)
            for (i, (p, q)) in zip(x, y).enumerated() { assertSemanticEqual(p, q, path: "\(path)[\(i)]", file: file, line: line) }
        case let (x as NSNumber, y as NSNumber) where CFGetTypeID(x) != CFBooleanGetTypeID() && CFGetTypeID(y) != CFBooleanGetTypeID():
            XCTAssertEqual(x.doubleValue, y.doubleValue, accuracy: 1e-6, "number at \(path)", file: file, line: line)
        case let (x as NSObject, y as NSObject):
            XCTAssertEqual(x, y, "value at \(path)", file: file, line: line)
        default:
            XCTAssertTrue(a == nil && b == nil, "mismatch at \(path): \(String(describing: a)) vs \(String(describing: b))", file: file, line: line)
        }
    }

    private func json(fromBase64 b64: String) throws -> Any {
        try JSONSerialization.jsonObject(with: XCTUnwrap(Data(base64Encoded: b64)))
    }

    // MARK: Content channel

    func testContentVectorsDecodeToTheSameMessage() throws {
        let c = try XCTUnwrap(try vectors()["constants"] as? [String: Any])
        let tsMs = try XCTUnwrap(c["tsUnixMs"] as? NSNumber).doubleValue
        for v in try section("content") {
            let name = v["name"] as? String ?? "?"
            let gm = try XCTUnwrap(v["groupMessage"] as? [String: Any])
            let m = try XCTUnwrap(GroupWireV2.decodeContent(try XCTUnwrap(v["plaintext"] as? String)), name)
            XCTAssertEqual(m.id.uuidString, gm["id"] as? String, name)
            XCTAssertEqual(m.groupId.uuidString, gm["groupId"] as? String, name)
            XCTAssertEqual(m.senderPublicKey, gm["senderPublicKey"] as? String, name)
            XCTAssertEqual((m.timestamp.timeIntervalSince1970 * 1000), tsMs, accuracy: 1, name)
            if let body = v["body"] as? String {
                XCTAssertEqual(String(data: m.encryptedContent, encoding: .utf8), body, name)
            }
            let env = try XCTUnwrap(v["envelope"] as? [String: Any])
            XCTAssertEqual(GroupWireV2.check(m, envelopeFrom: env["from"] as! String, envelopeGroupId: env["groupId"] as! String),
                           .accept, name)
        }
    }

    func testContentVectorsReEncodeToTheSameJSON() throws {
        for v in try section("content") {
            let name = v["name"] as? String ?? "?"
            let m = try XCTUnwrap(GroupWireV2.decodeContent(try XCTUnwrap(v["plaintext"] as? String)), name)
            // What iOS actually holds for its own outgoing text: `plaintextContent` = the body.
            var outgoing = m
            if !m.isSystemMessage { outgoing.plaintextContent = String(data: m.encryptedContent, encoding: .utf8) }
            outgoing.deliveryStatus = .pending
            let ours = try XCTUnwrap(GroupWireV2.encodeContent(outgoing), name)
            assertSemanticEqual(try json(fromBase64: ours), v["groupMessage"], path: name)
        }
    }

    func testMediaVectorCarriesTheStrippedGroupMessage() throws {
        let v = try vector("media", "photo")
        let key = try XCTUnwrap(v["keyMessage"] as? [String: Any])
        let m = try XCTUnwrap(GroupWireV2.decodeContent(try XCTUnwrap(key["groupMessage"] as? String)))
        XCTAssertEqual(m.mediaType, .photo)
        XCTAssertEqual(m.plaintextContent, "sunset")
        XCTAssertTrue(m.encryptedContent.isEmpty)
        let ours = try XCTUnwrap(GroupWireV2.encodeContent(m))
        assertSemanticEqual(try json(fromBase64: ours), v["groupMessage"], path: "photo")
        // §2.2 `image` is an alias of `photo`.
        let alias = try JSONDecoder().decode(MediaType.self, from: Data("\"image\"".utf8))
        XCTAssertEqual(alias, .photo)
    }

    func testReceiverRules() throws {
        for name in ["sender_mismatch", "system_actor_mismatch"] {
            let v = try vector("receiver", name)
            let gm = try JSONSerialization.data(withJSONObject: try XCTUnwrap(v["groupMessage"]))
            let m = try JSONDecoder().decode(GroupMessage.self, from: gm)
            guard case .drop = GroupWireV2.check(m, envelopeFrom: v["envelopeFrom"] as! String,
                                                 envelopeGroupId: m.groupId.uuidString) else {
                return XCTFail("\(name) must be dropped")
            }
        }
        // A group envelope carrying a state message is never rendered.
        let g = try vector("receiver", "group_update_on_group_channel")
        XCTAssertNil(GroupWireV2.decodeContent(g["plaintext"] as! String))
        // groupId mismatch between envelope and message ⇒ drop.
        let text = try XCTUnwrap(GroupWireV2.decodeContent(try vector("content", "text")["plaintext"] as! String))
        guard case .drop = GroupWireV2.check(text, envelopeFrom: text.senderPublicKey,
                                             envelopeGroupId: UUID().uuidString) else { return XCTFail("groupId mismatch") }
        // Version rules.
        for name in ["stale_definition", "equal_definition", "unversioned_definition"] {
            let v = try vector("receiver", name)
            XCTAssertEqual(GroupWireV2.versionAccepts(local: v["local"] as? Int, incoming: v["incoming"] as? Int),
                           v["apply"] as? Bool, name)
        }
    }

    // MARK: State channel

    func testStateDefinitionsRoundTrip() throws {
        let prefix = GroupWireV2.groupUpdatePrefix
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .iso8601
        for v in try section("state") {
            let name = v["name"] as? String ?? "?"
            let plaintext = try XCTUnwrap(v["plaintext"] as? String)
            XCTAssertTrue(plaintext.hasPrefix(prefix), name)
            let body = Data(plaintext.dropFirst(prefix.count).utf8)
            let j = try XCTUnwrap(v["json"] as? [String: Any])
            if j["members"] is [[String: Any]] {
                // Definition: decodes with the shipping decoder, re-encodes to the same JSON.
                let g = try dec.decode(MessageGroup.self, from: body)
                XCTAssertEqual(g.stateVersion, j["stateVersion"] as? Int, name)
                XCTAssertEqual(g.groupDescription, j["description"] as? String, name)
                let framed = try XCTUnwrap(GroupWireV2.framedDefinition(g), name)
                XCTAssertTrue(framed.hasPrefix(prefix))
                let ours = try JSONSerialization.jsonObject(with: Data(framed.dropFirst(prefix.count).utf8))
                assertSemanticEqual(ours, j, path: name)
            } else {
                assertSemanticEqual(try JSONSerialization.jsonObject(with: body), j, path: name)
            }
        }
    }

    func testCompanionsMatchVectors() throws {
        let c = try XCTUnwrap(try vectors()["constants"] as? [String: Any])
        let gid = try XCTUnwrap(UUID(uuidString: c["groupId"] as! String))
        let prefix = GroupWireV2.groupUpdatePrefix
        func body(_ s: String?) throws -> Any {
            let s = try XCTUnwrap(s)
            XCTAssertTrue(s.hasPrefix(prefix))
            return try JSONSerialization.jsonObject(with: Data(s.dropFirst(prefix.count).utf8))
        }
        assertSemanticEqual(try body(GroupWireV2.groupRenamed(groupId: gid, name: "Team 2")),
                            try vector("state", "rename_minimal")["json"], path: "rename_minimal")
        assertSemanticEqual(try body(GroupWireV2.memberAdded(groupId: gid, member: c["D"] as! String)),
                            try vector("state", "add_member_minimal")["json"], path: "add_member_minimal")
        assertSemanticEqual(try body(GroupWireV2.memberRemoved(groupId: gid, member: c["C"] as! String)),
                            try vector("state", "remove_member_minimal")["json"], path: "remove_member_minimal")
        assertSemanticEqual(try body(GroupWireV2.memberRemoved(groupId: gid, member: c["B"] as! String)),
                            try vector("state", "leave")["json"], path: "leave")
        assertSemanticEqual(try body(GroupWireV2.memberSyncRequest(groupId: gid, requester: c["B"] as! String)),
                            try vector("state", "member_sync_request")["json"], path: "member_sync_request")
    }

    // MARK: Self copies

    func testGroupSentCopy() throws {
        let v = try vector("sentCopy", "group_text_self_copy")
        let expected = try XCTUnwrap(v["plaintext"] as? [String: Any])
        let text = String(data: try JSONSerialization.data(withJSONObject: expected), encoding: .utf8)!
        let parsed = try XCTUnwrap(GroupWireV2.parseGroupSentCopy(text))
        let conv = expected["conversation"] as! [String: Any]
        XCTAssertEqual(parsed.groupId, conv["groupId"] as? String)
        XCTAssertEqual(parsed.msgId, expected["msgId"] as? String)
        XCTAssertNotNil(GroupWireV2.decodeContent(parsed.message))
        // A 1:1 sent-copy is not a group one, and vice versa.
        XCTAssertNil(V2MessageRouter.parseSentCopy(text))

        let c = try XCTUnwrap(try vectors()["constants"] as? [String: Any])
        let ours = try XCTUnwrap(GroupWireV2.groupSentCopyPayload(
            groupId: UUID(uuidString: c["groupId"] as! String)!, message: expected["message"] as! String,
            msgId: expected["msgId"] as! String,
            sentAt: Date(timeIntervalSince1970: (c["tsUnixMs"] as! NSNumber).doubleValue / 1000)))
        assertSemanticEqual(try JSONSerialization.jsonObject(with: ours), expected, path: "sentCopy")
    }

    func testKindsDriveWakeAndSelfCopy() throws {
        let kinds = try section("content").reduce(into: [String: GroupWireV2.BodyKind]()) { acc, v in
            let m = GroupWireV2.decodeContent(v["plaintext"] as! String)!
            acc[v["name"] as! String] = GroupWireV2.kind(of: m)
        }
        XCTAssertEqual(kinds["text"], .user)
        XCTAssertEqual(kinds["reply"], .user)
        XCTAssertEqual(kinds["reaction_add"], .reaction)
        XCTAssertEqual(kinds["reaction_remove"], .reaction)
        XCTAssertEqual(kinds["typing"], .typing)
        XCTAssertEqual(kinds["edit"], .command)
        XCTAssertEqual(kinds["delete"], .command)
    }
}

// MARK: - __LEGACY_COMPAT_2026_09_23__ transition window (`/v2/config.group_legacy_compat`)

final class GroupLegacyCompatTests: XCTestCase {

    private let before = Date(timeIntervalSince1970: 1_791_961_200 - 1)
    private let after = Date(timeIntervalSince1970: 1_791_961_200)

    func testSunsetIsOctober14NineParis() {
        XCTAssertEqual(GroupLegacyCompat.sunset.timeIntervalSince1970, 1_791_961_200) // 2026-10-14T07:00:00Z
    }

    func testIsActiveOnlyForExplicitTrueBeforeSunset() {
        XCTAssertFalse(GroupLegacyCompat.isActive(remote: nil, now: before))
        XCTAssertFalse(GroupLegacyCompat.isActive(remote: false, now: before))
        XCTAssertTrue(GroupLegacyCompat.isActive(remote: true, now: before))
        XCTAssertFalse(GroupLegacyCompat.isActive(remote: true, now: after), "flag ignored after the sunset")
    }

    func testConfigDecodesWithAndWithoutTheKey() throws {
        let without = #"{"v2_enabled":true,"rollout_percent":100,"min_build":0,"device_mailbox_enabled":true}"#
        let r0 = try JSONDecoder().decode(V2FeatureFlag.Remote.self, from: Data(without.utf8))
        XCTAssertNil(r0.group_legacy_compat)
        XCTAssertFalse(GroupLegacyCompat.isActive(remote: r0.group_legacy_compat, now: before))
        let with = #"{"v2_enabled":true,"rollout_percent":100,"min_build":0,"group_legacy_compat":true}"#
        let r1 = try JSONDecoder().decode(V2FeatureFlag.Remote.self, from: Data(with.utf8))
        XCTAssertEqual(r1.group_legacy_compat, true)
        XCTAssertTrue(GroupLegacyCompat.isActive(remote: r1.group_legacy_compat, now: before))
        let off = #"{"v2_enabled":true,"rollout_percent":100,"min_build":0,"group_legacy_compat":false}"#
        XCTAssertEqual(try JSONDecoder().decode(V2FeatureFlag.Remote.self, from: Data(off.utf8)).group_legacy_compat, false)
    }

    func testThisBuildAnnouncesGroupV2() {
        XCTAssertTrue(RatchetHealSignal.Caps.has(RatchetHealSignal.Caps.mine, RatchetHealSignal.Caps.groupV2))
        XCTAssertTrue(RatchetHealSignal.Caps.has(RatchetHealSignal.Caps.mine, RatchetHealSignal.Caps.resendList))
        XCTAssertEqual(RatchetHealSignal.Caps.groupV2, 2)
    }

    func testLegacyTargetsPartition() {
        let capsKey = "Q2Fwc0dyb3VwVjJQZWVyQUFBQUFBQUFBQUFBQUFBQUFBQQ=="
        let provenKey = "UHJvdmVuUGVlckJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQg=="
        let oldKey = "T2xkUGVlckNDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQw=="
        let resendOnly = "UmVzZW5kT25seURERERERERERERERERERERERERERERERA=="
        let caps: (String) -> Int = { k in
            switch k {
            case capsKey: return RatchetHealSignal.Caps.resendList | RatchetHealSignal.Caps.groupV2
            case resendOnly: return RatchetHealSignal.Caps.resendList   // iOS 143-145 bundle
            default: return 0
            }
        }
        let proven: Set<String> = [MessageManager.canonicalIdentity(provenKey)]
        let all = [capsKey, provenKey, oldKey, resendOnly]
        XCTAssertEqual(GroupLegacyCompat.legacyTargets(all, active: true, capsOf: caps, proven: proven),
                       [oldKey, resendOnly])
        XCTAssertEqual(GroupLegacyCompat.legacyTargets(all, active: false, capsOf: caps, proven: proven), [],
                       "compat off ⇒ v2 only, nobody gets the legacy copy")
    }

    func testProofNeedsAVersionedDefinition() {
        let d = UserDefaults(suiteName: "GroupLegacyCompatTests")!
        d.removePersistentDomain(forName: "GroupLegacyCompatTests")
        let peer = "UHJvb2ZQZWVyRUVFRUVFRUVFRUVFRUVFRUVFRUVFRUVFRQ=="
        // iOS ≤ 143 answered an Android sync_request over v2 with the stateVersion-less `created` form.
        GroupLegacyCompat.recordV2StateProof(peer: peer, json: #"{"type":"created","groupId":"X"}"#, defaults: d)
        XCTAssertTrue(GroupLegacyCompat.provenPeers(d).isEmpty)
        GroupLegacyCompat.recordV2StateProof(peer: peer, json: #"{"id":"X","name":"n","stateVersion":3}"#, defaults: d)
        XCTAssertEqual(GroupLegacyCompat.provenPeers(d), [MessageManager.canonicalIdentity(peer)])
        d.removePersistentDomain(forName: "GroupLegacyCompatTests")
    }

    func testLegacyUploadsFollowTheCompatState() {
        let key = V2FeatureFlag.remoteKey
        let saved = UserDefaults.standard.data(forKey: key)
        defer {
            if let saved { UserDefaults.standard.set(saved, forKey: key) } else { UserDefaults.standard.removeObject(forKey: key) }
        }
        UserDefaults.standard.set(Data(#"{"v2_enabled":true,"rollout_percent":100,"min_build":0}"#.utf8), forKey: key)
        XCTAssertFalse(GroupWireV2.legacyGroupUploadsAllowed)
        XCTAssertEqual(GroupLegacyCompat.legacyTargets(["T2xkUGVlckNDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQw=="]), [])
        UserDefaults.standard.set(Data(#"{"v2_enabled":true,"rollout_percent":100,"min_build":0,"group_legacy_compat":true}"#.utf8), forKey: key)
        XCTAssertEqual(GroupWireV2.legacyGroupUploadsAllowed, Date() < GroupLegacyCompat.sunset)
    }

    /// __LEAK_FIX_2026_09_25__ audit B: no readable legacy duplicate for a member v2 reached on
    /// a build that reads v2 groups; a pre-2026-09-04 build keeps its copy.
    func testStillNeedLegacyDropsOnlyReachedMembersThatReadV2Groups() {
        let v3: Set<String> = ["NEW_UNREACHED", "NEW_REACHED"]
        let out = GroupLegacyCompat.stillNeedLegacy(["OLD_REACHED", "NEW_REACHED", "NEW_UNREACHED", "OLD_UNREACHED"],
                                                    v2Reached: ["OLD_REACHED", "NEW_REACHED"],
                                                    readsV2Groups: { v3.contains($0) })
        XCTAssertEqual(out, ["OLD_REACHED", "NEW_UNREACHED", "OLD_UNREACHED"])
        XCTAssertEqual(GroupLegacyCompat.stillNeedLegacy([], v2Reached: ["X"], readsV2Groups: { _ in true }), [])
    }
}
