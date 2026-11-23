//
//  GroupCommentsTests.swift
//  OSHITests
//
//  __GROUP_COMMENTS_2026_11_21__ GROUP_INFO_SETTINGS_SPEC §2.12 — comments under
//  channel posts. The shared vectors (docs/fixtures/group_settings_v1/
//  definition_with_comments.json + comment_*.json), the receive rule of §2.12.4
//  in spec order, orphans, the delete right (rule D), who is woken (§2.12.3), who
//  is notified (§2.12.5), the settings round trip that must keep `comments`, then
//  the real v2 funnel into `MessageGroupManager.shared` for the §2.12.9 table.
//

import XCTest
@testable import OSHI

private enum CommentVectors {
    static let adminKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    static let memberKey = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8="
    static let groupId = UUID(uuidString: "5F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9")!
    static let postId = "0A1B2C3D-0000-4000-8000-000000000001"
    static let c1Id = "0A1B2C3D-0000-4000-8000-000000000002"
    static let c2Id = "0A1B2C3D-0000-4000-8000-000000000003"

    static func data(_ name: String) throws -> Data {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("docs/fixtures/group_settings_v1/\(name).json")
        return try Data(contentsOf: url)
    }

    /// A comment vector exactly as a receiver gets it: the base64 of the JSON is
    /// the ratchet plaintext (§2.12.9), decoded by the shipping decoder.
    static func message(_ name: String) throws -> GroupMessage {
        try XCTUnwrap(GroupWireV2.decodeContent(try data(name).base64EncodedString()), name)
    }

    static func definition() throws -> MessageGroup {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return try d.decode(MessageGroup.self, from: try data("definition_with_comments"))
    }
}

final class GroupCommentsPolicyTests: XCTestCase {
    private let A = CommentVectors.adminKey
    private let M = CommentVectors.memberKey
    private let stranger = "U3RyYW5nZXJLZXlDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQw=="

    private func parent(_ m: GroupMessage, comment: Bool = false, deleted: Bool = false) -> CommentParentInfo {
        CommentParentInfo(authorKey: m.senderPublicKey, isComment: comment, isDeleted: deleted)
    }

    // MARK: Vectors

    func testDefinitionWithCommentsDecodesAndIsActive() throws {
        let g = try CommentVectors.definition()
        let s = try XCTUnwrap(g.settings)
        XCTAssertTrue(s.comments)
        XCTAssertEqual(s.postPolicy, .admins)
        XCTAssertTrue(s.commentsActive)
        XCTAssertTrue(GroupCommentPolicy.isActive(g))
        XCTAssertEqual(g.stateVersion, 8)
    }

    func testCommentVectorsDecode() throws {
        let p = try CommentVectors.message("comment_post")
        XCTAssertNil(p.commentOn)
        XCTAssertEqual(GroupCommentPolicy.text(of: p), "Daily note: BTC weekly position 40% long.")

        let c1 = try CommentVectors.message("comment_top_level")
        XCTAssertEqual(c1.commentOn, CommentVectors.postId)
        XCTAssertEqual(GroupCommentPolicy.text(of: c1), "Why 40 and not 50?")
        XCTAssertTrue(GroupCommentPolicy.sameId(GroupCommentPolicy.replyTarget(of: c1), CommentVectors.postId),
                      "top level: replyTo == commentOn (no quote in the thread)")

        let c2 = try CommentVectors.message("comment_reply_to_comment")
        XCTAssertEqual(c2.commentOn, CommentVectors.postId, "a reply still names the POST in commentOn")
        XCTAssertEqual(GroupCommentPolicy.text(of: c2), "Vol targeting caps it at 40.")
        XCTAssertTrue(GroupCommentPolicy.sameId(GroupCommentPolicy.replyTarget(of: c2), CommentVectors.c1Id))

        let media = try CommentVectors.message("comment_with_media")
        XCTAssertEqual(media.mediaType, .photo)
        XCTAssertFalse(GroupCommentPolicy.isTextKind(media))
        XCTAssertTrue(GroupCommentPolicy.isTextKind(c1))
        XCTAssertTrue(GroupCommentPolicy.isTextKind(c2))
    }

    func testCommentOnIsComparedCaseInsensitivelyAndBounded() {
        XCTAssertEqual(GroupCommentPolicy.normalizedCommentOn(" 0a1b2c3d-0000-4000-8000-000000000001 "), CommentVectors.postId)
        XCTAssertNil(GroupCommentPolicy.normalizedCommentOn(""))
        XCTAssertNil(GroupCommentPolicy.normalizedCommentOn(String(repeating: "A", count: 65)))
        XCTAssertTrue(GroupCommentPolicy.sameId("abc", "ABC"))
    }

    // MARK: §2.12.4 — the receive rule, in the §2.12.9 order

    func testReceiveTableOnTheVectors() throws {
        let g = try CommentVectors.definition()
        let p = try CommentVectors.message("comment_post")
        let c1 = try CommentVectors.message("comment_top_level")
        let c2 = try CommentVectors.message("comment_reply_to_comment")

        XCTAssertEqual(GroupCommentPolicy.decide(p, sender: A, group: g, parent: nil, recentFromSender: 0), .notAComment)
        XCTAssertEqual(GroupCommentPolicy.decide(c1, sender: M, group: g, parent: parent(p), recentFromSender: 0),
                       .accept(postId: CommentVectors.postId))
        XCTAssertEqual(GroupCommentPolicy.decide(c2, sender: A, group: g, parent: parent(p), recentFromSender: 0),
                       .accept(postId: CommentVectors.postId), "an admin's comment is a comment, not a post")

        let plain = try CommentVectors.message("comment_member_plain_post")
        XCTAssertEqual(GroupCommentPolicy.decide(plain, sender: M, group: g, parent: nil, recentFromSender: 0), .notAComment)
        XCTAssertFalse(GroupSettingsPolicy.mayPost(sender: M, group: g), "rule 1 → §2.4 drops it (not-admin)")

        let onComment = try CommentVectors.message("comment_on_a_comment")
        XCTAssertEqual(GroupCommentPolicy.decide(onComment, sender: M, group: g, parent: parent(c1, comment: true), recentFromSender: 0),
                       .drop("bad-parent"))

        let media = try CommentVectors.message("comment_with_media")
        XCTAssertEqual(GroupCommentPolicy.decide(media, sender: M, group: g, parent: parent(p), recentFromSender: 0),
                       .drop("comment-kind"))
    }

    func testCommentsOffAdminBecomesPostMemberDropped() throws {
        var g = try CommentVectors.definition()
        g.settings?.comments = false
        let p = try CommentVectors.message("comment_post")
        let c1 = try CommentVectors.message("comment_top_level")
        XCTAssertEqual(GroupCommentPolicy.decide(c1, sender: M, group: g, parent: parent(p), recentFromSender: 0),
                       .drop("comments-off"))
        let c2 = try CommentVectors.message("comment_reply_to_comment")
        XCTAssertEqual(GroupCommentPolicy.decide(c2, sender: A, group: g, parent: parent(p), recentFromSender: 0),
                       .ordinary, "an admin's comment in comments-off mode is an ordinary post")
    }

    func testEveryoneGroupIgnoresCommentOn() throws {
        var g = try CommentVectors.definition()
        g.settings?.postPolicy = .everyone   // comments:true stays, harmless
        let c1 = try CommentVectors.message("comment_top_level")
        XCTAssertEqual(GroupCommentPolicy.decide(c1, sender: M, group: g, parent: nil, recentFromSender: 0), .ordinary)
        XCTAssertFalse(GroupCommentPolicy.isActive(g))
    }

    func testParentNotAdminBannedOrphanDeletedFlood() throws {
        var g = try CommentVectors.definition()
        let c1 = try CommentVectors.message("comment_top_level")
        // P authored by M (a member post kept from before channel mode).
        XCTAssertEqual(GroupCommentPolicy.decide(c1, sender: M, group: g,
                                                 parent: CommentParentInfo(authorKey: M, isComment: false, isDeleted: false),
                                                 recentFromSender: 0), .drop("parent-not-admin"))
        // Unknown parent → hold.
        XCTAssertEqual(GroupCommentPolicy.decide(c1, sender: M, group: g, parent: nil, recentFromSender: 0), .hold)
        // Deleted parent.
        XCTAssertEqual(GroupCommentPolicy.decide(c1, sender: M, group: g,
                                                 parent: CommentParentInfo(authorKey: "", isComment: false, isDeleted: true),
                                                 recentFromSender: 0), .drop("bad-parent"))
        // Flood: the 31st within 60 s.
        let p = try CommentVectors.message("comment_post")
        XCTAssertEqual(GroupCommentPolicy.decide(c1, sender: M, group: g, parent: parent(p), recentFromSender: 29),
                       .accept(postId: CommentVectors.postId))
        XCTAssertEqual(GroupCommentPolicy.decide(c1, sender: M, group: g, parent: parent(p), recentFromSender: 30),
                       .drop("flood"))
        // Banned (eviction tombstone), even before the parent is looked at.
        g.evictedMemberKeys = [M]
        XCTAssertEqual(GroupCommentPolicy.decide(c1, sender: M, group: g, parent: nil, recentFromSender: 0), .drop("banned"))
    }

    func testControlPayloadsAreNotTextComments() throws {
        let p = try CommentVectors.message("comment_post")
        for body in ["📍LOCATION📍{\"latitude\":1,\"longitude\":2}", "🛡️CHECK_IN🛡️{}",
                     "{\"type\":\"media\",\"mediaType\":\"photo\",\"content\":\"\"}", "📊POLL📊{}", "   "] {
            var m = try CommentVectors.message("comment_top_level")
            m.plaintextContent = nil
            m.encryptedContent = Data(body.utf8)
            XCTAssertFalse(GroupCommentPolicy.isTextKind(m), body)
            // Same inside the reply envelope.
            let wrapped = MessageWithReply(content: body, replyTo: ReplyMessage(
                originalMessageId: p.id.uuidString, originalSenderKey: A, originalText: "x",
                originalTimestamp: p.timestamp, originalMediaType: nil)).toMessageString()
            m.encryptedContent = Data(wrapped.utf8)
            XCTAssertFalse(GroupCommentPolicy.isTextKind(m), "wrapped " + body)
        }
        var broken = try CommentVectors.message("comment_top_level")
        broken.plaintextContent = nil
        broken.encryptedContent = Data("💬REPLY💬{not json".utf8)
        XCTAssertFalse(GroupCommentPolicy.isTextKind(broken))
    }

    func testLongCommentIsTruncatedInsideTheEnvelope() throws {
        var c = try CommentVectors.message("comment_top_level")
        let big = MessageWithReply(content: String(repeating: "é", count: 5000),
                                   replyTo: GroupCommentPolicy.envelope(of: GroupCommentPolicy.body(of: c))?.replyTo).toMessageString()
        c.plaintextContent = big
        c.encryptedContent = Data(big.utf8)
        let t = GroupCommentPolicy.truncatedBody(c)
        XCTAssertEqual(GroupCommentPolicy.text(of: t).utf16.count, 4096)
        XCTAssertTrue(GroupCommentPolicy.sameId(GroupCommentPolicy.replyTarget(of: t), CommentVectors.postId), "quote kept")
    }

    // MARK: Encode check (§2.12.9)

    func testSenderBuildsC1AsTheSpecRequires() throws {
        let p = try CommentVectors.message("comment_post")
        let c = GroupCommentPolicy.buildComment(groupId: CommentVectors.groupId, me: M, text: "Why 40 and not 50?",
                                                post: p, postText: GroupCommentPolicy.text(of: p),
                                                replyTo: nil, replyText: nil, at: Date())
        let b64 = try XCTUnwrap(GroupWireV2.encodeContent(c))
        let obj = try XCTUnwrap(JSONSerialization.jsonObject(with: try XCTUnwrap(Data(base64Encoded: b64))) as? [String: Any])
        XCTAssertEqual(obj["commentOn"] as? String, CommentVectors.postId, "UPPERCASE post id")
        let body = try XCTUnwrap(String(data: try XCTUnwrap(Data(base64Encoded: obj["encryptedContent"] as? String ?? "")), encoding: .utf8))
        XCTAssertTrue(body.hasPrefix("💬REPLY💬"))
        let env = try XCTUnwrap(GroupCommentPolicy.envelope(of: body))
        XCTAssertEqual(env.replyTo?.originalMessageId, CommentVectors.postId)
        XCTAssertEqual(env.content, "Why 40 and not 50?")
        XCTAssertEqual((obj["content"] as? String)?.hasPrefix("💬REPLY💬"), true, "Android `content` key carries the same body")
        // And it decodes back on a receiver as a comment of P.
        let back = try XCTUnwrap(GroupWireV2.decodeContent(b64))
        XCTAssertEqual(back.commentOn, CommentVectors.postId)

        // A reply to C1 quotes C1 and still names P.
        let c1 = try CommentVectors.message("comment_top_level")
        let r = GroupCommentPolicy.buildComment(groupId: CommentVectors.groupId, me: A, text: "Vol targeting caps it at 40.",
                                                post: p, postText: "", replyTo: c1, replyText: "Why 40 and not 50?", at: Date())
        XCTAssertEqual(r.commentOn, CommentVectors.postId)
        XCTAssertTrue(GroupCommentPolicy.sameId(GroupCommentPolicy.replyTarget(of: r), CommentVectors.c1Id))
    }

    // MARK: Sender rules (§2.12.3)

    func testSenderCheck() throws {
        let g = try CommentVectors.definition()
        let p = try CommentVectors.message("comment_post")
        let c1 = try CommentVectors.message("comment_top_level")
        XCTAssertEqual(GroupCommentPolicy.senderCheck(text: "hi", post: p, postDeleted: false, group: g, sentInLastMinute: 0), .ok)
        XCTAssertEqual(GroupCommentPolicy.senderCheck(text: "hi", post: p, postDeleted: false, group: g, sentInLastMinute: 19), .ok)
        XCTAssertEqual(GroupCommentPolicy.senderCheck(text: "hi", post: p, postDeleted: false, group: g, sentInLastMinute: 20), .tooFast,
                       "the 21st in a minute is refused locally")
        XCTAssertEqual(GroupCommentPolicy.senderCheck(text: "hi", post: c1, postDeleted: false, group: g, sentInLastMinute: 0), .badPost)
        XCTAssertEqual(GroupCommentPolicy.senderCheck(text: "hi", post: p, postDeleted: true, group: g, sentInLastMinute: 0), .badPost)
        XCTAssertEqual(GroupCommentPolicy.senderCheck(text: "hi", post: nil, postDeleted: false, group: g, sentInLastMinute: 0), .badPost)
        XCTAssertEqual(GroupCommentPolicy.senderCheck(text: "  ", post: p, postDeleted: false, group: g, sentInLastMinute: 0), .empty)
        var off = g; off.settings?.comments = false
        XCTAssertEqual(GroupCommentPolicy.senderCheck(text: "hi", post: p, postDeleted: false, group: off, sentInLastMinute: 0), .inactive)
        let memberPost = try CommentVectors.message("comment_member_plain_post")
        XCTAssertEqual(GroupCommentPolicy.senderCheck(text: "hi", post: memberPost, postDeleted: false, group: g, sentInLastMinute: 0), .badPost)
    }

    func testPushWakesOnlyTheAnsweredAuthor() {
        XCTAssertEqual(GroupCommentPolicy.pushTargets(replyTargetAuthor: nil, me: A), [], "top level wakes nobody")
        XCTAssertEqual(GroupCommentPolicy.pushTargets(replyTargetAuthor: M, me: A), [M])
        XCTAssertEqual(GroupCommentPolicy.pushTargets(replyTargetAuthor: A, me: A), [], "never ourselves")
    }

    func testNotificationOnlyForReplyToMeOrMention() throws {
        let c1 = try CommentVectors.message("comment_top_level")
        let c2 = try CommentVectors.message("comment_reply_to_comment")
        XCTAssertFalse(GroupCommentPolicy.notifies(c1, me: A, myCommentIds: []), "top level notifies nobody, not even the post author")
        XCTAssertTrue(GroupCommentPolicy.notifies(c2, me: M, myCommentIds: [CommentVectors.c1Id.lowercased()]), "A replied to M's comment")
        XCTAssertFalse(GroupCommentPolicy.notifies(c2, me: stranger, myCommentIds: []))
        var mention = c1
        let body = MessageWithReply(content: "cc " + GroupMentions.wireToken(for: stranger),
                                    replyTo: GroupCommentPolicy.envelope(of: GroupCommentPolicy.body(of: c1))?.replyTo).toMessageString()
        mention.plaintextContent = body
        mention.encryptedContent = Data(body.utf8)
        XCTAssertTrue(GroupCommentPolicy.notifies(mention, me: stranger, myCommentIds: []))
        XCTAssertFalse(GroupCommentPolicy.notifies(c2, me: A, myCommentIds: [CommentVectors.c1Id]), "never for my own comment")
    }

    // MARK: Rule D

    func testAdminDeletesAnyCommentAuthorOwnOthersNothing() throws {
        let g = try CommentVectors.definition()
        let c1 = try CommentVectors.message("comment_top_level")   // by M
        XCTAssertTrue(GroupCommentPolicy.mayDelete(actor: A, comment: c1, group: g), "admin, not author")
        XCTAssertTrue(GroupCommentPolicy.mayDelete(actor: M, comment: c1, group: g), "author")
        XCTAssertFalse(GroupCommentPolicy.mayDelete(actor: stranger, comment: c1, group: g))
        XCTAssertFalse(GroupCommentPolicy.mayDelete(actor: nil, comment: c1, group: g))

        // The v2 command gate agrees (target = the comment).
        func cmd(_ actor: String, _ target: String) -> GroupMessage {
            GroupMessage(id: UUID(), groupId: g.id, senderPublicKey: "SYSTEM", encryptedContent: Data(), timestamp: Date(),
                         systemMessageType: .messageDeleted,
                         systemMessageData: GroupSystemMessageData(actorPublicKey: actor, targetPublicKey: nil, deletedMessageId: target))
        }
        XCTAssertTrue(GroupWireV2.commandAllowed(cmd(A, c1.id.uuidString), target: c1, group: g))
        let p = try CommentVectors.message("comment_post")
        XCTAssertFalse(GroupWireV2.commandAllowed(cmd(M, p.id.uuidString), target: p, group: g),
                       "message_deleted of P by M is refused (not a comment, not M's)")
    }

    func testEditAuthorOnlyTenMinutes() throws {
        let c1 = try CommentVectors.message("comment_top_level")
        XCTAssertTrue(GroupCommentPolicy.mayEdit(me: M, comment: c1, now: c1.timestamp.addingTimeInterval(60)))
        XCTAssertFalse(GroupCommentPolicy.mayEdit(me: M, comment: c1, now: c1.timestamp.addingTimeInterval(601)))
        XCTAssertFalse(GroupCommentPolicy.mayEdit(me: A, comment: c1, now: c1.timestamp))
    }

    // MARK: Orphans

    func testOrphansAreCappedExpiredAndTakenByPost() throws {
        var o = GroupCommentOrphans()
        let c1 = try CommentVectors.message("comment_top_level")
        let t0 = Date()
        o.hold(c1, sender: M, now: t0)
        o.hold(c1, sender: M, now: t0)
        XCTAssertEqual(o.count(c1.groupId), 1, "never the same comment twice")
        for i in 0..<60 {
            var m = c1
            m = GroupMessage(id: UUID(), groupId: m.groupId, senderPublicKey: M, encryptedContent: m.encryptedContent,
                             timestamp: m.timestamp, plaintextContent: m.plaintextContent)
            m.commentOn = "OTHER-\(i)"
            o.hold(m, sender: M, now: t0.addingTimeInterval(Double(i)))
        }
        XCTAssertEqual(o.count(c1.groupId), 50, "≤ 50 per group, oldest dropped first")
        XCTAssertTrue(o.take(groupId: c1.groupId, postId: CommentVectors.postId, now: t0).isEmpty, "C1 was the oldest: dropped")

        var o2 = GroupCommentOrphans()
        o2.hold(c1, sender: M, now: t0)
        XCTAssertTrue(o2.take(groupId: c1.groupId, postId: CommentVectors.postId,
                              now: t0.addingTimeInterval(24 * 3600 + 1)).isEmpty, "24 h expiry")
        o2.hold(c1, sender: M, now: t0)
        XCTAssertEqual(o2.take(groupId: c1.groupId, postId: CommentVectors.postId.lowercased(), now: t0).count, 1)
        XCTAssertEqual(o2.count(c1.groupId), 0)
    }

    // MARK: Settings (§2.1 rule 2)

    func testSettingsRoundTripKeepsComments() throws {
        let g = try CommentVectors.definition()
        // Re-emitted by our definition encoder…
        let framed = try XCTUnwrap(GroupWireV2.framedDefinition(g))
        XCTAssertTrue(framed.contains("\"comments\":true"), framed)
        // …and parsed back.
        let s = try XCTUnwrap(g.settings)
        let back = try JSONDecoder().decode(GroupSettings.self, from: JSONEncoder().encode(s))
        XCTAssertTrue(back.comments)
        XCTAssertEqual(back, s)
        // An admin's save of ANOTHER setting keeps it.
        var edited = s
        edited.welcome = "Hello"
        XCTAssertTrue(GroupSettingsPolicy.merge(current: s, incoming: edited, fromAdmin: true)?.comments == true)
        // A missing subkey means default false (why every platform must ship it).
        XCTAssertFalse(GroupSettings(json: ["postPolicy": .string("admins")]).comments)
        XCTAssertFalse(GroupSettings(json: ["comments": .number(1)]).comments, "1 is not a bool")
        // A non-admin cannot turn it on.
        var off = s; off.comments = false
        XCTAssertEqual(GroupSettingsPolicy.merge(current: off, incoming: s, fromAdmin: false)?.comments, false)
    }

    func testAdminLogRecordsCommentsChanged() throws {
        let g = try CommentVectors.definition()
        var h = g
        h.settings?.comments = false
        let ev = GroupAdminLog.diff(old: g, new: h, actor: A)
        XCTAssertEqual(ev.map(\.kind), ["comments_changed"])
        XCTAssertEqual(ev.first?.detail, "off")
    }

    func testPluralAndStringsResolve() {
        XCTAssertEqual(GroupCommentsBar.countText(1), "1 comment")
        XCTAssertEqual(GroupCommentsBar.countText(3), "3 comments")
        XCTAssertEqual(GroupCommentsBar.label(count: 0), "Leave a comment")
        for k in ["gs_comments", "gs_comments_footer", "cm_leave_comment", "cm_thread_title", "cm_empty",
                  "cm_composer_hint", "cm_replying_to", "cm_disabled", "cm_post_unavailable", "cm_too_fast",
                  "cm_notif_reply", "cm_delete_comment"] {
            XCTAssertNotEqual(k.localized, k, k)
        }
        XCTAssertEqual(String(format: "cm_replying_to".localized, "Bob"), "Replying to Bob")
    }

    func testPostDetection() throws {
        let g = try CommentVectors.definition()
        let p = try CommentVectors.message("comment_post")
        XCTAssertTrue(GroupCommentPolicy.isPost(p, in: g))
        XCTAssertFalse(GroupCommentPolicy.isPost(try CommentVectors.message("comment_top_level"), in: g))
        XCTAssertFalse(GroupCommentPolicy.isPost(try CommentVectors.message("comment_member_plain_post"), in: g))
        var off = g; off.settings?.comments = false
        XCTAssertFalse(GroupCommentPolicy.isPost(p, in: off), "no bar when comments are off")
    }
}

// MARK: - The real funnel (§2.12.9 table, applied in order)

@MainActor
final class GroupCommentsWiringTests: XCTestCase {
    private let A = CommentVectors.adminKey
    private let M = CommentVectors.memberKey
    private let me = "Q29tbWVudHNTZWxmS2V5Q0NDQ0NDQ0NDQ0NDQ0NDQ0M="
    private let gid = CommentVectors.groupId
    private var savedPublicKey: String?

    override func setUp() async throws {
        try await super.setUp()
        savedPublicKey = UserDefaults.standard.string(forKey: "publicKey")
        UserDefaults.standard.set(me, forKey: "publicKey")
        clean()
    }

    override func tearDown() async throws {
        clean()
        if let savedPublicKey { UserDefaults.standard.set(savedPublicKey, forKey: "publicKey") }
        else { UserDefaults.standard.removeObject(forKey: "publicKey") }
        try await super.tearDown()
    }

    private func clean() {
        let gm = MessageGroupManager.shared
        gm.groups.removeAll { $0.id == gid }
        gm.groupMessages.removeValue(forKey: gid)
        GroupCommentStore.shared.forget(gid)
        GroupCommentStore.shared.receiveFlood.reset()
    }

    private func install(comments: Bool = true, evictM: Bool = false) throws {
        var g = try CommentVectors.definition()
        g.members.append(GroupMember(publicKey: me, alias: "Me", joinedAt: Date(timeIntervalSince1970: 0), isAdmin: false))
        g.settings?.comments = comments
        if evictM { g.evictedMemberKeys = [M] }
        MessageGroupManager.shared.groups.append(g)
        MessageGroupManager.shared.groupMessages[gid] = []
    }

    private func deliver(_ name: String, from: String) throws {
        let m = try CommentVectors.message(name)
        MessageGroupManager.shared.receiveV2GroupMessage(m, from: from, envelopeGroupId: gid.uuidString)
    }

    private var timeline: [GroupMessage] { MessageGroupManager.shared.groupMessages[gid] ?? [] }
    private var store: GroupCommentStore { GroupCommentStore.shared }
    private var postUUID: UUID { UUID(uuidString: CommentVectors.postId)! }

    private func command(_ type: GroupSystemMessageType, actor: String, target: String) -> GroupMessage {
        GroupMessage(id: UUID(), groupId: gid, senderPublicKey: "SYSTEM", encryptedContent: Data(), timestamp: Date(),
                     isRead: false, messageChainIndex: 0, systemMessageType: type,
                     systemMessageData: GroupSystemMessageData(actorPublicKey: actor, targetPublicKey: nil,
                                                               deletedMessageId: type == .messageDeleted ? target : nil,
                                                               editedMessageId: type == .messageEdited ? target : nil,
                                                               editedContent: type == .messageEdited ? "edited" : nil))
    }

    func testSpecTableInOrder() async throws {
        try install()
        try deliver("comment_post", from: A)
        XCTAssertEqual(timeline.filter { !$0.isSystemMessage }.map(\.id.uuidString), [CommentVectors.postId], "P is a timeline post")
        let unreadAfterPost = MessageGroupManager.shared.getUnreadCount(for: gid)

        try deliver("comment_top_level", from: M)
        try deliver("comment_reply_to_comment", from: A)
        XCTAssertEqual(store.comments(groupId: gid, postId: postUUID).map(\.id.uuidString), [CommentVectors.c1Id, CommentVectors.c2Id])
        XCTAssertEqual(store.count(groupId: gid, postId: postUUID), 2)
        XCTAssertEqual(timeline.filter { !$0.isSystemMessage }.count, 1, "comments are NOT timeline rows")
        XCTAssertEqual(MessageGroupManager.shared.getUnreadCount(for: gid), unreadAfterPost, "comments never bump unread")
        XCTAssertEqual(MessageGroupManager.shared.getLastMessage(for: gid)?.id.uuidString, CommentVectors.postId,
                       "comments never become the preview")

        try deliver("comment_member_plain_post", from: M)       // not-admin
        try deliver("comment_on_a_comment", from: M)            // bad-parent
        try deliver("comment_with_media", from: M)              // comment-kind
        XCTAssertEqual(timeline.filter { !$0.isSystemMessage }.count, 1)
        XCTAssertEqual(store.count(groupId: gid, postId: postUUID), 2)

        // Reaction 👍 on C1 by M, group reactions "some" [👍 ❤️ 🔥] → applied.
        let reaction = ReactionPayload(messageId: CommentVectors.c1Id, emoji: .thumbsUp, senderPublicKey: M,
                                       senderName: "M", timestamp: Date(), action: .add).toMessageString()
        let rm = GroupMessage(id: UUID(), groupId: gid, senderPublicKey: M, encryptedContent: Data(reaction.utf8),
                              timestamp: Date())
        MessageGroupManager.shared.receiveV2GroupMessage(rm, from: M, envelopeGroupId: gid.uuidString)
        let deadline = Date().addingTimeInterval(2)
        while Date() < deadline, !MessageReactionManager.shared.hasUserReacted(messageId: CommentVectors.c1Id, senderPublicKey: M, emoji: .thumbsUp) {
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertTrue(MessageReactionManager.shared.hasUserReacted(messageId: CommentVectors.c1Id, senderPublicKey: M, emoji: .thumbsUp))

        // An edit of C1 by A (not the author) is refused; by M it lands.
        MessageGroupManager.shared.receiveV2GroupMessage(command(.messageEdited, actor: A, target: CommentVectors.c1Id),
                                                         from: A, envelopeGroupId: gid.uuidString)
        XCTAssertNil(store.comment(groupId: gid, id: CommentVectors.c1Id)?.editedContent)
        MessageGroupManager.shared.receiveV2GroupMessage(command(.messageEdited, actor: M, target: CommentVectors.c1Id),
                                                         from: M, envelopeGroupId: gid.uuidString)
        XCTAssertEqual(store.comment(groupId: gid, id: CommentVectors.c1Id)?.editedContent, "edited")

        // message_deleted of P by M → refused.
        MessageGroupManager.shared.receiveV2GroupMessage(command(.messageDeleted, actor: M, target: CommentVectors.postId),
                                                         from: M, envelopeGroupId: gid.uuidString)
        XCTAssertTrue(timeline.contains { $0.id.uuidString == CommentVectors.postId })

        // message_deleted of C1 by A (admin, not author) → deleted.
        MessageGroupManager.shared.receiveV2GroupMessage(command(.messageDeleted, actor: A, target: CommentVectors.c1Id),
                                                         from: A, envelopeGroupId: gid.uuidString)
        XCTAssertNil(store.comment(groupId: gid, id: CommentVectors.c1Id))
        XCTAssertEqual(store.count(groupId: gid, postId: postUUID), 1)

        // M deletes C2 (A's) → refused; A deletes own C2 over the legacy leg → deleted.
        MessageGroupManager.shared.receiveV2GroupMessage(command(.messageDeleted, actor: M, target: CommentVectors.c2Id),
                                                         from: M, envelopeGroupId: gid.uuidString)
        XCTAssertNotNil(store.comment(groupId: gid, id: CommentVectors.c2Id))
        MessageGroupManager.shared.receiveGroupMessage(command(.messageDeleted, actor: M, target: CommentVectors.c2Id))
        XCTAssertNotNil(store.comment(groupId: gid, id: CommentVectors.c2Id), "legacy leg: same rule D")
        MessageGroupManager.shared.receiveGroupMessage(command(.messageDeleted, actor: A, target: CommentVectors.c2Id))
        XCTAssertNil(store.comment(groupId: gid, id: CommentVectors.c2Id))
    }

    func testCommentBeforePostIsHeldThenStored() throws {
        try install()
        try deliver("comment_top_level", from: M)
        XCTAssertEqual(store.count(groupId: gid, postId: postUUID), 0)
        XCTAssertEqual(store.orphans.count(gid), 1, "held in memory")
        XCTAssertTrue(timeline.filter { !$0.isSystemMessage }.isEmpty)
        try deliver("comment_post", from: A)
        XCTAssertEqual(store.count(groupId: gid, postId: postUUID), 1, "replayed as a comment when P arrives")
        XCTAssertEqual(store.orphans.count(gid), 0)
        XCTAssertEqual(timeline.filter { !$0.isSystemMessage }.count, 1)
    }

    func testCommentsOffMemberDroppedAdminBecomesPost() throws {
        try install(comments: false)
        try deliver("comment_post", from: A)
        try deliver("comment_top_level", from: M)
        XCTAssertEqual(store.count(groupId: gid, postId: postUUID), 0)
        XCTAssertEqual(timeline.filter { !$0.isSystemMessage }.count, 1, "member comment dropped (comments-off)")
        try deliver("comment_reply_to_comment", from: A)
        let posts = timeline.filter { !$0.isSystemMessage }
        XCTAssertEqual(posts.count, 2, "admin's comment became an ordinary post")
        XCTAssertNil(posts.last?.commentOn, "commentOn ignored and stripped")
    }

    func testEvictedCommenterIsDropped() throws {
        try install(evictM: true)
        try deliver("comment_post", from: A)
        try deliver("comment_top_level", from: M)
        XCTAssertEqual(store.count(groupId: gid, postId: postUUID), 0)
        XCTAssertEqual(store.orphans.count(gid), 0)
    }

    func testParentNotAdminIsDropped() throws {
        try install()
        // A member post kept from before channel mode, with P's id.
        var p = try CommentVectors.message("comment_post")
        p = GroupMessage(id: p.id, groupId: gid, senderPublicKey: M, encryptedContent: p.encryptedContent, timestamp: p.timestamp)
        MessageGroupManager.shared.groupMessages[gid] = [p]
        try deliver("comment_top_level", from: M)
        XCTAssertEqual(store.count(groupId: gid, postId: postUUID), 0)
        XCTAssertEqual(store.orphans.count(gid), 0)
    }

    func testReceiverFloodLimit() throws {
        try install()
        try deliver("comment_post", from: A)
        let base = try CommentVectors.message("comment_top_level")
        for _ in 0..<35 {
            let m = GroupMessage(id: UUID(), groupId: gid, senderPublicKey: M, encryptedContent: base.encryptedContent,
                                 timestamp: Date())
            var c = m
            c.commentOn = CommentVectors.postId
            MessageGroupManager.shared.receiveV2GroupMessage(c, from: M, envelopeGroupId: gid.uuidString)
        }
        XCTAssertEqual(store.count(groupId: gid, postId: postUUID), 30, "more than 30 in 60 s from one sender: dropped")
    }
}
