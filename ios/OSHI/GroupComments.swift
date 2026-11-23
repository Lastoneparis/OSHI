//
//  GroupComments.swift
//  OSHI
//
//  __GROUP_COMMENTS_2026_11_21__ Telegram-style comments under channel posts.
//  Spec: docs/GROUP_INFO_SETTINGS_SPEC.md §2.12 (branch feat/group-public-link).
//  Vectors: docs/fixtures/group_settings_v1/comment_*.json.
//
//  A comment is an ordinary E2E GroupMessage of the SAME group with one extra
//  top-level key, `commentOn` = the post id, and a body that is always a
//  `💬REPLY💬` envelope (so old builds show it as a reply bubble). Nothing new on
//  the server.
//
//  Everything that decides is a PURE function in `GroupCommentPolicy` (the
//  receive rule of §2.12.4 in spec order, the sender checks of §2.12.3, the
//  delete right, who is woken, who is notified) so OSHITests asserts the
//  shipping rules. `GroupCommentStore` holds the accepted comments OUTSIDE
//  `groupMessages`: a comment is not a timeline row, so every reader of the
//  timeline (unread, preview, search, export, devsync, web) keeps ignoring it
//  without having to be taught.
//

import Foundation
import SwiftUI

// MARK: - Pure rules

/// What the receiver knows about the message a comment points at.
struct CommentParentInfo: Equatable {
    var authorKey: String
    /// The parent is itself a comment (stored in the comment store, or carries `commentOn`).
    var isComment: Bool
    /// The parent was deleted for everyone (tombstone known to this device).
    var isDeleted: Bool
}

enum CommentReceiveVerdict: Equatable {
    /// Rule 1: no `commentOn` — today's rules (§2.4 post filter) apply.
    case notAComment
    /// Rules 2/3: `commentOn` is ignored, the message is an ordinary one.
    case ordinary
    /// Rule 4e: store as a comment of this post id (UPPERCASE).
    case accept(postId: String)
    /// Rule 4c: parent unknown — hold as an orphan.
    case hold
    /// Every DROP, with the spec's reason string.
    case drop(String)
}

enum GroupCommentPolicy {
    static let maxCommentOnLength = 64
    /// §2.12.2: the message limit, in UTF-16 units.
    static let maxCommentUnits = 4096
    /// §2.12.3 sender limit / §2.12.4 d receiver limit, per 60 s.
    static let senderFloodLimit = 20
    static let receiverFloodLimit = 30
    static let floodWindow: TimeInterval = 60
    /// §2.12.4 orphans: 24 h, ≤ 50 per group, memory only.
    static let orphanTTL: TimeInterval = 24 * 3600
    static let orphanCap = 50
    /// §2.12.3 edit window = the message policy (10 minutes).
    static let editWindow: TimeInterval = 600

    /// `commentOn` as the receiver keeps it: trimmed, non-empty, ≤ 64, UPPERCASE.
    nonisolated static func normalizedCommentOn(_ raw: String?) -> String? {
        guard let t = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty,
              t.count <= maxCommentOnLength else { return nil }
        return t.uppercased()
    }

    nonisolated static func sameId(_ a: String?, _ b: String?) -> Bool {
        guard let a, let b else { return false }
        return a.caseInsensitiveCompare(b) == .orderedSame
    }

    /// §2.12.1 Active in OUR copy.
    nonisolated static func isActive(_ group: MessageGroup?) -> Bool {
        group?.settings?.commentsActive == true
    }

    /// The body the message carries (text messages keep it in `encryptedContent`).
    nonisolated static func body(of m: GroupMessage) -> String {
        if let p = m.plaintextContent, !p.isEmpty { return p }
        return String(data: m.encryptedContent, encoding: .utf8) ?? ""
    }

    /// The reply envelope of a comment, or nil for a plain-text body.
    nonisolated static func envelope(of body: String) -> MessageWithReply? {
        guard body.hasPrefix(MessageWithReply.replyPrefix) else { return nil }
        let json = String(body.dropFirst(MessageWithReply.replyPrefix.count))
        guard let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(MessageWithReply.self, from: data)
    }

    /// The words of a comment (inside the envelope, or the plain body).
    nonisolated static func text(of m: GroupMessage) -> String {
        if let e = m.editedContent, !e.isEmpty {
            return envelope(of: e)?.content ?? e
        }
        let b = body(of: m)
        return envelope(of: b)?.content ?? b
    }

    /// `replyTo.originalMessageId` of a comment, or nil.
    nonisolated static func replyTarget(of m: GroupMessage) -> String? {
        envelope(of: body(of: m))?.replyTo?.originalMessageId
    }

    /// §2.12.2 kinds: text only. False for media (`mediaType`, which is also how a
    /// `v2file` arrives here), location, check-in, poll, media JSON, contact, any
    /// other control sentinel, or a `💬REPLY💬` that does not parse.
    nonisolated static func isTextKind(_ m: GroupMessage) -> Bool {
        if m.mediaType != nil || m.mediaFileName != nil || m.videoNote == true { return false }
        if m.isSystemMessage { return false }
        let b = body(of: m)
        let inner: String
        if b.hasPrefix(MessageWithReply.replyPrefix) {
            guard let e = envelope(of: b), e.replyTo != nil else { return false }
            inner = e.content
        } else {
            inner = b
        }
        if inner.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return false }
        if OSHIControlPayload.isControl(inner) { return false }
        if PollMessage.isPollMessage(inner) { return false }
        if inner.hasPrefix("{"), inner.contains("\"type\":\"media\"") || inner.contains("\"type\": \"media\"") {
            return false
        }
        return true
    }

    /// §2.12.4 — the receive rule, in spec order. `sender` = authenticated sender.
    /// `parent` = the stored message whose id == `commentOn`, or nil when unknown.
    /// `recentFromSender` = comments ACCEPTED from this sender in this group in
    /// the last 60 s (receiver counter).
    nonisolated static func decide(_ m: GroupMessage,
                                   sender: String,
                                   group: MessageGroup,
                                   parent: CommentParentInfo?,
                                   recentFromSender: Int) -> CommentReceiveVerdict {
        // 1.
        guard let postId = m.commentOn, !postId.isEmpty else { return .notAComment }
        let s = group.settings ?? GroupSettings()
        let admin = GroupSettingsPolicy.isAdmin(sender, in: group)
        // 2.
        guard s.postPolicy == .admins else { return .ordinary }
        // 3.
        guard s.comments else { return admin ? .ordinary : .drop("comments-off") }
        // 4a.
        guard isTextKind(m) else { return .drop("comment-kind") }
        // 4b.
        let sc = MessageManager.canonicalIdentity(sender)
        if (group.evictedMemberKeys ?? []).contains(where: { MessageManager.canonicalIdentity($0) == sc }) {
            return .drop("banned")
        }
        // 4c.
        guard let parent else { return .hold }
        if parent.isComment || parent.isDeleted { return .drop("bad-parent") }
        guard GroupSettingsPolicy.isAdmin(parent.authorKey, in: group) else { return .drop("parent-not-admin") }
        // 4d.
        if recentFromSender >= receiverFloodLimit { return .drop("flood") }
        // 4e.
        return .accept(postId: postId.uppercased())
    }

    /// §2.12.6: does this TIMELINE message get a comments bar? Any non-system post
    /// authored by a current admin, in an Active group.
    nonisolated static func isPost(_ m: GroupMessage, in group: MessageGroup?) -> Bool {
        guard let group, isActive(group), !m.isSystemMessage, m.commentOn == nil else { return false }
        let b = m.mediaType == nil ? body(of: m) : ""
        if !b.isEmpty, OSHIControlPayload.isSilent(b) { return false }
        return GroupSettingsPolicy.isAdmin(m.senderPublicKey, in: group)
    }

    enum SendCheck: Equatable { case ok, inactive, badPost, tooFast, empty }

    /// §2.12.3 — may we send a comment on `post` now? `sentInLastMinute` = our own
    /// comments in this group in the last 60 s.
    nonisolated static func senderCheck(text: String, post: GroupMessage?, postDeleted: Bool,
                                        group: MessageGroup?, sentInLastMinute: Int) -> SendCheck {
        guard let group, isActive(group) else { return .inactive }
        guard let post, !postDeleted, post.commentOn == nil, !post.isSystemMessage,
              GroupSettingsPolicy.isAdmin(post.senderPublicKey, in: group) else { return .badPost }
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return .empty }
        if sentInLastMinute >= senderFloodLimit { return .tooFast }
        return .ok
    }

    /// §2.12.2 the comment as it goes on the wire. `replyTo` = the comment being
    /// answered, or nil for a top-level comment (then the quote names the post).
    nonisolated static func buildComment(id: UUID = UUID(), groupId: UUID, me: String, text: String,
                                         post: GroupMessage, postText: String,
                                         replyTo: GroupMessage?, replyText: String?,
                                         at: Date) -> GroupMessage {
        let target = replyTo ?? post
        let quoted = replyTo != nil ? (replyText ?? "") : postText
        let quote = ReplyMessage(originalMessageId: target.id.uuidString.uppercased(),
                                 originalSenderKey: target.senderPublicKey,
                                 originalText: String(OSHIControlPayload.previewText(for: quoted).prefix(180)),
                                 originalTimestamp: target.timestamp,
                                 originalMediaType: replyTo == nil ? target.mediaType?.rawValue : nil)
        let clipped = GroupSettings.truncateUTF16(text, max: maxCommentUnits)
        let body = MessageWithReply(content: clipped, replyTo: quote).toMessageString()
        var m = GroupMessage(id: id, groupId: groupId, senderPublicKey: me,
                             encryptedContent: Data(body.utf8), timestamp: at, isRead: true,
                             messageChainIndex: 0, plaintextContent: body, deliveryStatus: .pending)
        m.commentOn = post.id.uuidString.uppercased()
        return m
    }

    /// §2.12.2 receivers truncate a body longer than 4096 UTF-16 units (inside
    /// the envelope, so the quote survives).
    nonisolated static func truncatedBody(_ m: GroupMessage) -> GroupMessage {
        let b = body(of: m)
        guard b.utf16.count > maxCommentUnits else { return m }
        var out = m
        let fixed: String
        if let e = envelope(of: b) {
            fixed = MessageWithReply(content: GroupSettings.truncateUTF16(e.content, max: maxCommentUnits),
                                     replyTo: e.replyTo).toMessageString()
        } else {
            fixed = GroupSettings.truncateUTF16(b, max: maxCommentUnits)
        }
        out.encryptedContent = Data(fixed.utf8)
        out.plaintextContent = fixed
        return out
    }

    /// §2.12.3 push wake-up: only the author of the comment replied to, if not us.
    /// A top-level comment (or a reply to the post) wakes nobody.
    nonisolated static func pushTargets(replyTargetAuthor: String?, me: String) -> [String] {
        guard let a = replyTargetAuthor, !a.isEmpty,
              MessageManager.canonicalIdentity(a) != MessageManager.canonicalIdentity(me) else { return [] }
        return [a]
    }

    /// §2.12.5: notify me only for a reply to one of MY comments or an @mention of me.
    nonisolated static func notifies(_ comment: GroupMessage, me: String?, myCommentIds: Set<String>) -> Bool {
        guard let me, !me.isEmpty else { return false }
        let mc = MessageManager.canonicalIdentity(me)
        if MessageManager.canonicalIdentity(comment.senderPublicKey) == mc { return false }
        if let target = replyTarget(of: comment),
           myCommentIds.contains(where: { sameId($0, target) }) { return true }
        return GroupMentions.wireMentions(in: text(of: comment)).contains { MessageManager.canonicalIdentity($0) == mc }
    }

    /// §2.12.4 D: author OR a current admin of our copy may delete a comment.
    nonisolated static func mayDelete(actor: String?, comment: GroupMessage, group: MessageGroup) -> Bool {
        guard let actor, !actor.isEmpty else { return false }
        if MessageManager.canonicalIdentity(actor) == MessageManager.canonicalIdentity(comment.senderPublicKey) { return true }
        return GroupSettingsPolicy.isAdmin(actor, in: group)
    }

    /// Edits: author only, within the 10-minute window (shipped message policy).
    nonisolated static func mayEdit(me: String?, comment: GroupMessage, now: Date = Date()) -> Bool {
        guard let me else { return false }
        return MessageManager.canonicalIdentity(me) == MessageManager.canonicalIdentity(comment.senderPublicKey)
            && now.timeIntervalSince(comment.timestamp) <= editWindow
    }
}

// MARK: - In-memory windows (flood counters, orphans)

/// Sliding 60 s counters, keyed by "group|sender". Memory only (spec).
final class GroupCommentFloodCounter {
    private var hits: [String: [Date]] = [:]
    private let lock = NSLock()

    func count(_ key: String, now: Date = Date()) -> Int {
        lock.lock(); defer { lock.unlock() }
        let fresh = (hits[key] ?? []).filter { now.timeIntervalSince($0) < GroupCommentPolicy.floodWindow }
        hits[key] = fresh
        return fresh.count
    }

    func record(_ key: String, now: Date = Date()) {
        lock.lock(); defer { lock.unlock() }
        hits[key, default: []].append(now)
    }

    func reset() {
        lock.lock(); defer { lock.unlock() }
        hits.removeAll()
    }
}

/// §2.12.4 orphans: comments whose post we do not hold yet. ≤ 50 per group,
/// 24 h, memory only; oldest dropped first.
struct GroupCommentOrphans {
    struct Held { var message: GroupMessage; var sender: String; var heldAt: Date }
    private(set) var byGroup: [UUID: [Held]] = [:]

    mutating func hold(_ m: GroupMessage, sender: String, now: Date = Date()) {
        var list = (byGroup[m.groupId] ?? []).filter { now.timeIntervalSince($0.heldAt) < GroupCommentPolicy.orphanTTL }
        guard !list.contains(where: { $0.message.id == m.id }) else { byGroup[m.groupId] = list; return }
        list.append(Held(message: m, sender: sender, heldAt: now))
        if list.count > GroupCommentPolicy.orphanCap { list.removeFirst(list.count - GroupCommentPolicy.orphanCap) }
        byGroup[m.groupId] = list
    }

    /// Removes and returns the held comments waiting for `postId` (unexpired).
    mutating func take(groupId: UUID, postId: String, now: Date = Date()) -> [Held] {
        let list = (byGroup[groupId] ?? []).filter { now.timeIntervalSince($0.heldAt) < GroupCommentPolicy.orphanTTL }
        let ready = list.filter { GroupCommentPolicy.sameId($0.message.commentOn, postId) }
        let rest = list.filter { !GroupCommentPolicy.sameId($0.message.commentOn, postId) }
        byGroup[groupId] = rest.isEmpty ? nil : rest
        return ready
    }

    func count(_ groupId: UUID) -> Int { byGroup[groupId]?.count ?? 0 }

    mutating func forget(_ groupId: UUID) { byGroup.removeValue(forKey: groupId) }
}

// MARK: - The comment store

/// Accepted comments, per group, OUTSIDE the timeline. Sealed at rest like the
/// group messages (FileStorage → AtRestCipher).
@MainActor
final class GroupCommentStore: ObservableObject {
    static let shared = GroupCommentStore()

    @Published private(set) var comments: [UUID: [GroupMessage]] = [:]
    /// Ids deleted for everyone on this device (memory), so a late comment on a
    /// deleted post is `bad-parent` instead of a 24 h orphan.
    private var deletedIds: [UUID: Set<String>] = [:]
    var orphans = GroupCommentOrphans()
    let receiveFlood = GroupCommentFloodCounter()
    let sendFlood = GroupCommentFloodCounter()

    private let key = "groupComments_v1"
    private var loaded = false

    private init() {}

    private func ensureLoaded() {
        guard !loaded else { return }
        loaded = true
        guard !MessageGroupManager.isRunningUnitTests else { return }
        let raw: [String: [GroupMessage]] = FileStorage.shared.load(forKey: key) ?? [:]
        var out: [UUID: [GroupMessage]] = [:]
        for (k, v) in raw { if let id = UUID(uuidString: k) { out[id] = v } }
        comments = out
    }

    private func persist() {
        guard !MessageGroupManager.isRunningUnitTests else { return }
        let snapshot = Dictionary(uniqueKeysWithValues: comments.map { ($0.key.uuidString, $0.value) })
        let k = key
        DispatchQueue.global(qos: .utility).async { FileStorage.shared.save(snapshot, forKey: k) }
    }

    // MARK: Reads

    func all(_ groupId: UUID) -> [GroupMessage] {
        ensureLoaded()
        return comments[groupId] ?? []
    }

    /// Chronological comments of a post (case-insensitive id).
    func comments(groupId: UUID, postId: UUID) -> [GroupMessage] {
        all(groupId).filter { GroupCommentPolicy.sameId($0.commentOn, postId.uuidString) }
            .sorted { $0.timestamp < $1.timestamp }
    }

    /// §2.12.5 count shown on the bar: stored, non-deleted comments (deletes remove them).
    func count(groupId: UUID, postId: UUID) -> Int {
        all(groupId).reduce(0) { $0 + (GroupCommentPolicy.sameId($1.commentOn, postId.uuidString) ? 1 : 0) }
    }

    func comment(groupId: UUID, id: String) -> GroupMessage? {
        all(groupId).first { GroupCommentPolicy.sameId($0.id.uuidString, id) }
    }

    func contains(groupId: UUID, id: UUID) -> Bool { comment(groupId: groupId, id: id.uuidString) != nil }

    func isDeleted(groupId: UUID, id: String) -> Bool {
        deletedIds[groupId]?.contains(id.uppercased()) == true
    }

    func myCommentIds(groupId: UUID, me: String?) -> Set<String> {
        guard let me else { return [] }
        let mc = MessageManager.canonicalIdentity(me)
        return Set(all(groupId).filter { MessageManager.canonicalIdentity($0.senderPublicKey) == mc }.map { $0.id.uuidString })
    }

    // MARK: Writes

    func insert(_ m: GroupMessage) {
        ensureLoaded()
        var list = comments[m.groupId] ?? []
        guard !list.contains(where: { $0.id == m.id }) else { return }
        list.append(m)
        comments[m.groupId] = list
        persist()
    }

    func update(groupId: UUID, id: UUID, _ f: (inout GroupMessage) -> Void) {
        ensureLoaded()
        guard var list = comments[groupId], let i = list.firstIndex(where: { $0.id == id }) else { return }
        f(&list[i])
        comments[groupId] = list
        persist()
    }

    @discardableResult
    func remove(groupId: UUID, id: UUID) -> GroupMessage? {
        ensureLoaded()
        guard var list = comments[groupId], let i = list.firstIndex(where: { $0.id == id }) else { return nil }
        let gone = list.remove(at: i)
        comments[groupId] = list.isEmpty ? nil : list
        markDeleted(groupId: groupId, id: id.uuidString)
        persist()
        return gone
    }

    func markDeleted(groupId: UUID, id: String) {
        deletedIds[groupId, default: []].insert(id.uppercased())
    }

    /// History cleared / group left or deleted (§2.12.4 "cleaned when the group
    /// history is cleared").
    func forget(_ groupId: UUID) {
        ensureLoaded()
        comments.removeValue(forKey: groupId)
        deletedIds.removeValue(forKey: groupId)
        orphans.forget(groupId)
        persist()
    }

    func clearAll() {
        comments.removeAll()
        deletedIds.removeAll()
        orphans = GroupCommentOrphans()
        receiveFlood.reset()
        sendFlood.reset()
        persist()
    }
}
