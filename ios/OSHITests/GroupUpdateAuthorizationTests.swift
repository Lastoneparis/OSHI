//
//  GroupUpdateAuthorizationTests.swift
//  OSHITests
//
//  __GROUP_UPDATE_AUTHORIZATION_2026_08_16__
//
//  Exercises the REAL shipping decision functions — `MessageGroupManager
//  .authorizeGroupUpdate` and `.applyGroupUpdate` — not a copy of their rules.
//  Both are pure, which is why they can be asserted here at all; the wholesale
//  `groups[index] = updatedGroup` they replaced could only ever be tested by
//  driving a live manager.
//
//  WHAT THIS PROVES
//    • a member cannot promote themselves to admin by sending a group definition
//      that says so — the escalation that made every admin-gated feature (bot
//      approval included) a self-service checkbox;
//    • an update from a non-member is ignored;
//    • a replayed older definition cannot undo the current state;
//    • an admin-only group does not accept a rename or a member list from a
//      plain member, while an open group does — matching `canAddMembers`;
//    • legacy peers that carry neither a version nor an authenticated sender
//      keep working, minus the admin set.
//
//  WHAT IT DOES NOT PROVE
//    Delivery. Whether `updatedBy` is actually threaded by every transport is a
//    wiring question, asserted on device via the log line the authorizer emits.
//

import XCTest
@testable import OSHI

final class GroupUpdateAuthorizationTests: XCTestCase {

    // MARK: - Fixtures

    private let adminKey  = "QWRtaW5LZXlBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE="
    private let memberKey = "TWVtYmVyS2V5QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQg=="
    private let strangerKey = "U3RyYW5nZXJLZXlDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQw=="

    private func makeGroup(type: GroupType = .collaborative,
                           name: String = "Refuge",
                           version: Int? = nil,
                           memberIsAdmin: Bool = false) -> MessageGroup {
        var group = MessageGroup(
            id: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!,
            name: name,
            type: type,
            adminPublicKey: adminKey,
            members: [
                GroupMember(publicKey: adminKey, alias: "Admin", joinedAt: Date(timeIntervalSince1970: 0), isAdmin: true),
                GroupMember(publicKey: memberKey, alias: "Membre", joinedAt: Date(timeIntervalSince1970: 0), isAdmin: memberIsAdmin)
            ],
            createdAt: Date(timeIntervalSince1970: 0),
            lastActivity: Date(timeIntervalSince1970: 0)
        )
        group.stateVersion = version
        return group
    }

    private func isAdmin(_ group: MessageGroup, _ key: String) -> Bool {
        group.members.first { $0.publicKey == key }?.isAdmin == true
    }

    // MARK: - Rule 2 — authority over the admin set

    /// The bug this whole change exists for: a plain member sends a definition in
    /// which they are an admin. Before, that was simply stored.
    func testMemberCannotPromoteItself() {
        let current = makeGroup()
        var forged = current
        forged.members = [
            GroupMember(publicKey: adminKey, alias: "Admin", joinedAt: Date(timeIntervalSince1970: 0), isAdmin: true),
            GroupMember(publicKey: memberKey, alias: "Membre", joinedAt: Date(timeIntervalSince1970: 0), isAdmin: true)
        ]

        let decision = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: forged, sender: memberKey)
        XCTAssertTrue(decision.apply)
        XCTAssertTrue(decision.keepAdminSet, "the admin set must never come from a non-admin")

        let merged = MessageGroupManager.applyGroupUpdate(current: current, incoming: forged, decision: decision)
        XCTAssertFalse(isAdmin(merged, memberKey), "self-promotion survived the merge")
        XCTAssertTrue(isAdmin(merged, adminKey), "the real admin was not preserved")
    }

    /// A newly introduced member cannot arrive pre-promoted either.
    func testNewMemberCannotArrivePrePromoted() {
        let current = makeGroup()
        var incoming = current
        incoming.members.append(
            GroupMember(publicKey: strangerKey, alias: "Nouveau", joinedAt: Date(), isAdmin: true))

        let decision = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: incoming, sender: memberKey)
        let merged = MessageGroupManager.applyGroupUpdate(current: current, incoming: incoming, decision: decision)

        XCTAssertTrue(merged.members.contains { $0.publicKey == strangerKey },
                      "an open group must still accept the new member")
        XCTAssertFalse(isAdmin(merged, strangerKey), "…but not as an admin")
    }

    /// An admin keeps full authority, including promoting someone.
    func testAdminMayPromote() {
        let current = makeGroup()
        var incoming = current
        incoming.members = [
            GroupMember(publicKey: adminKey, alias: "Admin", joinedAt: Date(timeIntervalSince1970: 0), isAdmin: true),
            GroupMember(publicKey: memberKey, alias: "Membre", joinedAt: Date(timeIntervalSince1970: 0), isAdmin: true)
        ]

        let decision = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: incoming, sender: adminKey)
        XCTAssertEqual(decision.reason, "admin")
        XCTAssertFalse(decision.keepAdminSet)

        let merged = MessageGroupManager.applyGroupUpdate(current: current, incoming: incoming, decision: decision)
        XCTAssertTrue(isAdmin(merged, memberKey), "an admin's promotion must go through")
    }

    /// The admin set is compared on the canonical key spelling, so a base64url
    /// rendering of the same identity is still recognised as the admin.
    func testAdminRecognisedAcrossKeySpellings() {
        let current = makeGroup()
        let base64url = adminKey
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")

        let decision = MessageGroupManager.authorizeGroupUpdate(
            current: current, incoming: current, sender: base64url)
        XCTAssertEqual(decision.reason, "admin", "same identity, other spelling — must still be the admin")
    }

    // MARK: - Rule 2b — standing

    func testUpdateFromNonMemberIsIgnored() {
        let current = makeGroup()
        var incoming = current
        incoming.name = "Détourné"

        let decision = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: incoming, sender: strangerKey)
        XCTAssertFalse(decision.apply)
        XCTAssertEqual(decision.reason, "sender-not-a-member")
    }

    // MARK: - Rule 1 — version

    func testStaleVersionIsIgnored() {
        let current = makeGroup(version: 7)
        var replay = makeGroup(name: "Ancien nom", version: 3)
        replay.members = [GroupMember(publicKey: adminKey, alias: nil, joinedAt: Date(), isAdmin: true)]

        let decision = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: replay, sender: adminKey)
        XCTAssertFalse(decision.apply, "a replay of an older definition must not undo the current state")
        XCTAssertEqual(decision.reason, "stale-version")
    }

    func testEqualVersionFromAdminStillApplies() {
        let current = makeGroup(version: 4)
        let incoming = makeGroup(name: "Renommé", version: 4)
        let decision = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: incoming, sender: adminKey)
        XCTAssertTrue(decision.apply, "concurrent edits at the same version stay last-writer-wins")
    }

    /// The compatibility rule that matters: a peer that sends no version at all
    /// (every Android build, every shipped iOS build) must not read as stale.
    func testVersionlessPeerIsNotTreatedAsStale() {
        let current = makeGroup(version: 12)
        let incoming = makeGroup(name: "Renommé", version: nil)

        let decision = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: incoming, sender: adminKey)
        XCTAssertTrue(decision.apply, "a versionless peer must keep working")
        XCTAssertNotEqual(decision.reason, "stale-version")
    }

    // MARK: - Rule 3 — group policy

    func testAdminOnlyGroupRejectsMemberRename() {
        let current = makeGroup(type: .adminOnly, name: "Officiel")
        var incoming = current
        incoming.name = "Renommé par un membre"

        let decision = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: incoming, sender: memberKey)
        XCTAssertTrue(decision.keepName)
        XCTAssertTrue(decision.keepMembership)

        let merged = MessageGroupManager.applyGroupUpdate(current: current, incoming: incoming, decision: decision)
        XCTAssertEqual(merged.name, "Officiel")
    }

    func testOpenGroupAcceptsMemberRename() {
        let current = makeGroup(type: .collaborative, name: "Refuge")
        var incoming = current
        incoming.name = "Refuge Nord"

        let decision = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: incoming, sender: memberKey)
        XCTAssertFalse(decision.keepName, "an open group already lets any member rename (canAddMembers)")

        let merged = MessageGroupManager.applyGroupUpdate(current: current, incoming: incoming, decision: decision)
        XCTAssertEqual(merged.name, "Refuge Nord")
    }

    /// Cosmetic fields keep flowing from a member of an OPEN group…
    func testCosmeticFieldsStillFlowFromAMemberOfAnOpenGroup() {
        let current = makeGroup(type: .collaborative)
        var incoming = current
        incoming.pinnedMessageId = UUID()
        incoming.pinnedBy = memberKey

        let decision = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: incoming, sender: memberKey)
        let merged = MessageGroupManager.applyGroupUpdate(current: current, incoming: incoming, decision: decision)
        XCTAssertEqual(merged.pinnedMessageId, incoming.pinnedMessageId,
                       "a downgrade must not become a sync outage")
    }

    /// …but in an ADMIN-ONLY group the group's face stays the admin's
    /// (__GROUP_TYPE_TAKEOVER_2026_11_20__, desktop be19d428).
    func testAdminOnlyMemberCannotChangePinPictureOrDescription() {
        let current = makeGroup(type: .adminOnly)
        var incoming = current
        incoming.pinnedMessageId = UUID()
        incoming.pinnedBy = memberKey
        incoming.groupDescription = "pris"
        incoming.groupPictureData = Data([1, 2, 3])
        incoming.avatar = "🏴"

        let decision = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: incoming, sender: memberKey)
        let merged = MessageGroupManager.applyGroupUpdate(current: current, incoming: incoming, decision: decision)
        XCTAssertEqual(merged.pinnedMessageId, current.pinnedMessageId)
        XCTAssertEqual(merged.groupDescription, current.groupDescription)
        XCTAssertEqual(merged.groupPictureData, current.groupPictureData)
        XCTAssertEqual(merged.avatar, current.avatar)
    }

    /// __GROUP_TYPE_TAKEOVER_2026_11_20__ A member cannot flip an admin-only
    /// group to collaborative (which would then let them do everything).
    func testMemberCannotChangeTheGroupType() {
        let current = makeGroup(type: .adminOnly)
        var incoming = current
        incoming.type = .collaborative
        let d = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: incoming, sender: memberKey)
        XCTAssertEqual(MessageGroupManager.applyGroupUpdate(current: current, incoming: incoming, decision: d).type, .adminOnly)

        // Also from an open group: type is admin-only business.
        let open = makeGroup(type: .collaborative)
        var toAdminOnly = open
        toAdminOnly.type = .adminOnly
        let d2 = MessageGroupManager.authorizeGroupUpdate(current: open, incoming: toAdminOnly, sender: memberKey)
        XCTAssertEqual(MessageGroupManager.applyGroupUpdate(current: open, incoming: toAdminOnly, decision: d2).type, .collaborative)

        let d3 = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: incoming, sender: adminKey)
        XCTAssertEqual(MessageGroupManager.applyGroupUpdate(current: current, incoming: incoming, decision: d3).type, .collaborative,
                       "the admin still may")
    }

    /// __GROUP_VERSION_FREEZE_2026_11_20__ Int.max from a member froze the
    /// group: every later admin definition was "stale-version".
    func testSenderChosenVersionCannotFreezeTheGroup() {
        let current = makeGroup(type: .collaborative, version: 5)
        var incoming = current
        incoming.stateVersion = Int.max
        let d = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: incoming, sender: memberKey)
        let merged = MessageGroupManager.applyGroupUpdate(current: current, incoming: incoming, decision: d)
        XCTAssertEqual(merged.stateVersion, 5 + MessageGroupManager.maxVersionStep)

        var next = merged
        next.stateVersion = (merged.stateVersion ?? 0) + 1
        XCTAssertTrue(MessageGroupManager.authorizeGroupUpdate(current: merged, incoming: next, sender: adminKey).apply,
                      "the admin's next definition must still apply")

        // Admin-only group: a member may not move the version at all.
        let ao = makeGroup(type: .adminOnly, version: 5)
        var bump = ao
        bump.stateVersion = 6
        let d2 = MessageGroupManager.authorizeGroupUpdate(current: ao, incoming: bump, sender: memberKey)
        XCTAssertEqual(MessageGroupManager.applyGroupUpdate(current: ao, incoming: bump, decision: d2).stateVersion, 5)

        XCTAssertEqual(MessageGroupManager.boundedVersion(current: Int.max, incoming: Int.max, mayAdvance: true), Int.max)
        XCTAssertEqual(MessageGroupManager.boundedVersion(current: nil, incoming: 3, mayAdvance: true), 3)
        XCTAssertNil(MessageGroupManager.boundedVersion(current: nil, incoming: 3, mayAdvance: false))
    }

    /// __GROUP_ADMIN_KICK_2026_11_20__ An open-group member may edit the
    /// roster but not drop the admin, re-add an evicted key, or tombstone anyone.
    func testOpenGroupMemberCannotKickTheAdminOrReviveTheEvicted() {
        var current = makeGroup(type: .collaborative)
        current.evictedMemberKeys = [strangerKey]
        var incoming = current
        incoming.members = [
            GroupMember(publicKey: memberKey, alias: nil, joinedAt: Date(), isAdmin: false),
            GroupMember(publicKey: strangerKey, alias: nil, joinedAt: Date(), isAdmin: false),
        ]
        incoming.evictedMemberKeys = [strangerKey, adminKey]
        let d = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: incoming, sender: memberKey)
        let merged = MessageGroupManager.applyGroupUpdate(current: current, incoming: incoming, decision: d)
        XCTAssertTrue(merged.members.contains { $0.publicKey == adminKey && $0.isAdmin }, "admin was dropped")
        XCTAssertFalse(merged.members.contains { $0.publicKey == strangerKey }, "evicted key came back")
        XCTAssertEqual(merged.evictedMemberKeys, [strangerKey], "a member tombstoned someone")
    }

    // MARK: - Legacy transports

    /// __UNAUTHENTICATED_GROUP_UPDATE_2026_11_20__ Mesh and the cloud paths
    /// carry no authenticated sender: whoever knows the group UUID can send
    /// on them. They may still move cosmetic fields, never the roster, the
    /// name or the admin set — a roster write from nobody is how an outsider
    /// adds their own key and starts receiving every member's message keys.
    func testUnauthenticatedTransportCannotTouchRosterNameOrAdmins() {
        let current = makeGroup()
        let attacker = "attacker-key-not-a-member"
        var incoming = current
        incoming.name = "Depuis le mesh"
        incoming.members = [
            GroupMember(publicKey: adminKey, alias: nil, joinedAt: Date(), isAdmin: true),
            GroupMember(publicKey: memberKey, alias: nil, joinedAt: Date(), isAdmin: true),
            GroupMember(publicKey: attacker, alias: nil, joinedAt: Date(), isAdmin: true)
        ]
        incoming.pinnedMessageId = UUID()

        let decision = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: incoming, sender: nil)
        XCTAssertTrue(decision.apply, "cosmetic sync must keep working")
        XCTAssertEqual(decision.reason, "unauthenticated-transport")
        XCTAssertTrue(decision.keepMembership)
        XCTAssertTrue(decision.keepName)

        let merged = MessageGroupManager.applyGroupUpdate(current: current, incoming: incoming, decision: decision)
        XCTAssertEqual(merged.name, current.name)
        XCTAssertFalse(merged.members.contains { $0.publicKey == attacker },
                       "an unauthenticated update planted a member")
        XCTAssertEqual(Set(merged.members.map { $0.publicKey }), Set(current.members.map { $0.publicKey }))
        XCTAssertFalse(isAdmin(merged, memberKey), "and it cannot hand out admin")
        XCTAssertEqual(merged.pinnedMessageId, incoming.pinnedMessageId)

        // The single-op forms (`member_added` / `member_removed` /
        // `group_renamed`) consult the same decision with `incoming == current`.
        let op = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: current, sender: nil)
        XCTAssertTrue(op.keepMembership && op.keepName)
        let empty = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: current, sender: "")
        XCTAssertTrue(empty.keepMembership && empty.keepName)
    }

    // MARK: - Wire compatibility

    // MARK: - F3 — eviction tombstone (__GROUP_EVICTION_TOMBSTONE_2026_09_10__)

    /// LE défaut réparé, vu de la fonction pure de fusion: une définition entrante
    /// qui « oublie » une éviction (périmée, ou forgée par le membre retiré) ne
    /// doit PAS ré-ouvrir la porte. L'ensemble évincé est une UNION qui ne
    /// rétrécit jamais, et tout évincé est retiré des membres fusionnés.
    func testEvictionTombstoneNeverShrinksAndDropsTheMemberFromMembership() {
        var current = makeGroup(type: .collaborative)
        current.evictedMemberKeys = [strangerKey]   // stranger a été retiré ici

        // L'entrant (par ex. renvoyé par un pair en retard, ou par l'évincé
        // lui-même) ne connaît pas l'éviction ET réintroduit le stranger comme membre.
        var incoming = current
        incoming.evictedMemberKeys = nil
        incoming.members.append(
            GroupMember(publicKey: strangerKey, alias: "Revenant", joinedAt: Date(), isAdmin: false))

        // Groupe ouvert: un membre peut modifier l'appartenance ⇒ decision.apply,
        // keepMembership == false. C'est exactement le cas dangereux.
        let decision = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: incoming, sender: memberKey)
        XCTAssertTrue(decision.apply)
        XCTAssertFalse(decision.keepMembership)

        let merged = MessageGroupManager.applyGroupUpdate(current: current, incoming: incoming, decision: decision)
        XCTAssertEqual(merged.evictedMemberKeys, [strangerKey], "l'éviction ne doit jamais disparaître")
        XCTAssertFalse(merged.members.contains { $0.publicKey == strangerKey },
                       "un membre évincé ne doit pas pouvoir se réintroduire via une mise à jour")
    }

    /// L'union additionne les évictions des deux côtés (aucun n'est perdu), et
    /// nil des deux côtés reste nil (aucun peer legacy cassé).
    func testEvictionUnionMergesBothSidesAndStaysNilWhenEmpty() {
        var current = makeGroup()
        current.evictedMemberKeys = [strangerKey]
        var incoming = current
        incoming.evictedMemberKeys = [memberKey]   // l'autre côté a évincé quelqu'un d'autre

        let decision = MessageGroupManager.authorizeGroupUpdate(current: current, incoming: incoming, sender: adminKey)
        let merged = MessageGroupManager.applyGroupUpdate(current: current, incoming: incoming, decision: decision)
        XCTAssertEqual(Set(merged.evictedMemberKeys ?? []), Set([strangerKey, memberKey]),
                       "l'union doit contenir les évictions des DEUX côtés")

        // Aucune éviction nulle part ⇒ reste nil (compat legacy).
        var a = makeGroup(); a.evictedMemberKeys = nil
        var b = a; b.evictedMemberKeys = nil
        let d2 = MessageGroupManager.authorizeGroupUpdate(current: a, incoming: b, sender: adminKey)
        let m2 = MessageGroupManager.applyGroupUpdate(current: a, incoming: b, decision: d2)
        XCTAssertNil(m2.evictedMemberKeys, "nil ∪ nil doit rester nil")
    }

    /// La pierre tombale survit à un aller-retour JSON, et son absence décode nil.
    func testEvictionTombstoneWireRoundTrip() throws {
        var group = makeGroup()
        group.evictedMemberKeys = [strangerKey]
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let decoded = try decoder.decode(MessageGroup.self, from: try encoder.encode(group))
        XCTAssertEqual(decoded.evictedMemberKeys, [strangerKey])

        group.evictedMemberKeys = nil
        let legacy = try decoder.decode(MessageGroup.self, from: try encoder.encode(group))
        XCTAssertNil(legacy.evictedMemberKeys, "absent doit décoder nil, jamais []")
    }

    /// `stateVersion` must survive a JSON round trip, and its absence must decode
    /// to nil rather than 0 — the difference between "legacy peer" and
    /// "permanently stale peer".
    func testStateVersionWireRoundTrip() throws {
        var group = makeGroup(version: 9)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601

        let encoded = try encoder.encode(group)
        let decoded = try decoder.decode(MessageGroup.self, from: encoded)
        XCTAssertEqual(decoded.stateVersion, 9)

        group.stateVersion = nil
        let legacy = try decoder.decode(MessageGroup.self, from: try encoder.encode(group))
        XCTAssertNil(legacy.stateVersion, "absent must decode as nil, never as 0")
    }
}
