//
//  GroupSettings.swift
//  OSHI
//
//  __GROUP_INFO_SETTINGS_2026_11_20__ Telegram-style group settings.
//  Spec: docs/GROUP_INFO_SETTINGS_SPEC.md (branch feat/group-public-link).
//  Vectors: docs/fixtures/group_settings_v1/.
//
//  Everything that decides — parse/sanitise, the receive rule, the join decision,
//  the reaction and post filters, the admin-log diff — is a PURE function here so
//  OSHITests asserts the shipping code, not a copy of its rules. The manager and
//  the views only call these.
//

import Foundation
import CryptoKit

// MARK: - Lenient JSON value (strict about types, never throws on content)

/// One JSON value, decoded WITHOUT coercion: `1` is a number, never a Bool, and
/// `"yes"` is a string. §2.1 says a subkey of the wrong type takes its default,
/// which `JSONDecoder`'s typed decode cannot express without failing the whole
/// definition (iOS 1.0.43 drops a definition that throws — GM:220).
enum GroupJSONValue: Decodable, Equatable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([GroupJSONValue])
    case object([String: GroupJSONValue])

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null; return }
        // Bool FIRST: Foundation's decoder refuses Bool for a JSON number, so a
        // `1` falls through to `.number` and a `true` never reaches it.
        if let b = try? c.decode(Bool.self) { self = .bool(b); return }
        if let d = try? c.decode(Double.self) { self = .number(d); return }
        if let s = try? c.decode(String.self) { self = .string(s); return }
        if let a = try? c.decode([GroupJSONValue].self) { self = .array(a); return }
        if let o = try? c.decode([String: GroupJSONValue].self) { self = .object(o); return }
        self = .null
    }

    var bool: Bool? { if case .bool(let b) = self { return b }; return nil }
    var string: String? { if case .string(let s) = self { return s }; return nil }
    var int: Int? {
        if case .number(let d) = self, d.isFinite, d == d.rounded(), abs(d) < 1e15 { return Int(d) }
        return nil
    }
    var array: [GroupJSONValue]? { if case .array(let a) = self { return a }; return nil }
}

// MARK: - The `settings` object (§2.1)

struct GroupSettings: Codable, Equatable {
    enum Visibility: String { case `private`, `public` }
    enum Reactions: String { case all, some, none }
    enum PostPolicy: String { case everyone, admins }

    static let maxWelcome = 1000
    static let maxAllowedReactions = 12
    static let maxReactionUnits = 16
    static let maxDescription = 500
    /// §2.3 default set offered by the "Some reactions" editor.
    static let defaultReactionChoices = ["👍", "❤️", "🔥", "😂", "😮", "😢", "🙏", "👏", "🎉", "👎"]

    var v: Int = 1
    var visibility: Visibility = .private
    var handle: String? = nil
    var showPreview: Bool = false
    var inviteToken: String? = nil
    var reactions: Reactions = .all
    var allowedReactions: [String] = []
    var welcome: String = ""
    var postPolicy: PostPolicy = .everyone
    var adminDMs: Bool = true
    var dissolved: Bool = false
    /// __GROUP_COMMENTS_2026_11_21__ §2.12.1 members may comment under posts when
    /// `postPolicy == .admins`. MUST be parsed and re-emitted (§2.1 rule 2: a save
    /// by code that dropped it would reset it to false on every receiver).
    var comments: Bool = false

    init() {}

    /// §2.12.1 Active ⇔ channel mode AND comments on.
    var commentsActive: Bool { postPolicy == .admins && comments }

    var isPublic: Bool { visibility == .public && handle != nil }

    /// `"some"` with an empty list behaves as `"none"` (§2.1).
    var effectiveReactions: Reactions {
        reactions == .some && allowedReactions.isEmpty ? .none : reactions
    }

    // MARK: Handle

    /// `^[a-z0-9_]{5,32}$` after lower-casing (§2.1, §3.1 step 1). Returns the
    /// normalised handle, or nil.
    static func normalizedHandle(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let h = raw.trimmingCharacters(in: .whitespaces).lowercased()
        guard (5...32).contains(h.unicodeScalars.count),
              h.unicodeScalars.allSatisfy({ ("a"..."z").contains($0) || ("0"..."9").contains($0) || $0 == "_" })
        else { return nil }
        return h
    }

    static func publicLink(handle: String) -> String { "https://oshi-messenger.com/g/\(handle)" }

    // MARK: Invite token (§3.2)

    /// 16 random bytes, base64url without padding (22 chars).
    static func newInviteToken() -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func validInviteToken(_ s: String?) -> String? {
        guard let s, (16...64).contains(s.count),
              s.unicodeScalars.allSatisfy({ CharacterSet.alphanumerics.contains($0) && $0.isASCII || $0 == "-" || $0 == "_" })
        else { return nil }
        return s
    }

    /// Constant-time comparison of two tokens (§3.3 rule 5).
    static func tokensMatch(_ a: String?, _ b: String?) -> Bool {
        guard let a, let b else { return false }
        let x = Array(a.utf8), y = Array(b.utf8)
        guard x.count == y.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<x.count { diff |= x[i] ^ y[i] }
        return diff == 0
    }

    // MARK: String limits

    /// Truncate to at most `max` UTF-16 units without splitting a character.
    static func truncateUTF16(_ s: String, max: Int) -> String {
        if s.utf16.count <= max { return s }
        var out = ""
        var units = 0
        for ch in s {
            let n = String(ch).utf16.count
            if units + n > max { break }
            out.append(ch)
            units += n
        }
        return out
    }

    // MARK: Parse (§2.1 sanitisation)

    init(json o: [String: GroupJSONValue]) {
        if let v = o["v"]?.int { self.v = v }
        let handle = GroupSettings.normalizedHandle(o["handle"]?.string)
        self.handle = handle
        if o["visibility"]?.string == "public", handle != nil { visibility = .public } else { visibility = .private }
        showPreview = o["showPreview"]?.bool ?? false
        inviteToken = GroupSettings.validInviteToken(o["inviteToken"]?.string)
        reactions = o["reactions"]?.string.flatMap(Reactions.init(rawValue:)) ?? .all
        allowedReactions = Array((o["allowedReactions"]?.array ?? [])
            .compactMap { $0.string }
            .filter { (1...GroupSettings.maxReactionUnits).contains($0.utf16.count) }
            .prefix(GroupSettings.maxAllowedReactions))
        welcome = GroupSettings.truncateUTF16(o["welcome"]?.string ?? "", max: GroupSettings.maxWelcome)
        postPolicy = o["postPolicy"]?.string.flatMap(PostPolicy.init(rawValue:)) ?? .everyone
        adminDMs = o["adminDMs"]?.bool ?? true
        dissolved = o["dissolved"]?.bool ?? false
        comments = o["comments"]?.bool ?? false
    }

    init(from decoder: Decoder) throws {
        let raw = (try? decoder.singleValueContainer().decode([String: GroupJSONValue].self)) ?? [:]
        self.init(json: raw)
    }

    private enum K: String, CodingKey {
        case v, visibility, handle, showPreview, inviteToken, reactions, allowedReactions
        case welcome, postPolicy, adminDMs, dissolved, comments
    }

    /// Every subkey is emitted (absent ≠ default for a reader that predates a key);
    /// `handle` / `inviteToken` only when set.
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: K.self)
        try c.encode(1, forKey: .v)
        try c.encode(visibility.rawValue, forKey: .visibility)
        try c.encodeIfPresent(handle, forKey: .handle)
        try c.encode(showPreview, forKey: .showPreview)
        try c.encodeIfPresent(inviteToken, forKey: .inviteToken)
        try c.encode(reactions.rawValue, forKey: .reactions)
        try c.encode(allowedReactions, forKey: .allowedReactions)
        try c.encode(welcome, forKey: .welcome)
        try c.encode(postPolicy.rawValue, forKey: .postPolicy)
        try c.encode(adminDMs, forKey: .adminDMs)
        try c.encode(dissolved, forKey: .dissolved)
        try c.encode(comments, forKey: .comments)
    }
}

// MARK: - Receive rules and filters

enum GroupSettingsPolicy {

    /// `admin(sender)` ⇔ canonical key in OUR copy's admin set (spec "Who is admin").
    nonisolated static func isAdmin(_ key: String?, in group: MessageGroup) -> Bool {
        guard let key, !key.isEmpty else { return false }
        let k = MessageManager.canonicalIdentity(key)
        return group.members.contains { $0.isAdmin && MessageManager.canonicalIdentity($0.publicKey) == k }
    }

    /// §2.1 receiver rule. `fromAdmin` must be the authorizer's verdict on OUR copy
    /// (`decision.reason == "admin"`); an unauthenticated transport is never admin.
    nonisolated static func merge(current: GroupSettings?, incoming: GroupSettings?, fromAdmin: Bool) -> GroupSettings? {
        guard let incoming else { return current }          // 1. absent ⇒ keep ours
        guard fromAdmin else { return current }             // 3./4. not admin ⇒ ignore whole object
        var out = incoming                                  // 2. replace…
        if current?.dissolved == true { out.dissolved = true } // …except `dissolved`, OR-ed
        return out
    }

    /// §2.2: description — absent keeps ours, `""` clears, ≤ 500 on receive.
    nonisolated static func mergeDescription(current: String?, incoming: String?) -> String? {
        guard let incoming else { return current }
        return GroupSettings.truncateUTF16(incoming, max: GroupSettings.maxDescription)
    }

    /// Variation selectors are not part of an emoji's identity for this check:
    /// "❤" and "❤️" are the same reaction whichever keyboard typed it.
    nonisolated static func emojiKey(_ s: String) -> String {
        String(String.UnicodeScalarView(s.unicodeScalars.filter { $0.value != 0xFE0F && $0.value != 0xFE0E }))
    }

    /// §2.3 — may an `add` of `emoji` be applied / offered in this group?
    nonisolated static func reactionAllowed(_ emoji: String, settings: GroupSettings?) -> Bool {
        guard let settings else { return true }
        switch settings.effectiveReactions {
        case .all: return true
        case .none: return false
        case .some:
            let k = emojiKey(emoji)
            return settings.allowedReactions.contains { emojiKey($0) == k }
        }
    }

    /// §2.3 receive filter: `remove` is always accepted.
    nonisolated static func acceptsIncomingReaction(isRemove: Bool, emoji: String, group: MessageGroup?) -> Bool {
        if isRemove { return true }
        return reactionAllowed(emoji, settings: group?.settings)
    }

    /// §2.4 — may `sender` post a content message here? Bots and locally generated
    /// SYSTEM rows are not members' posts.
    nonisolated static func mayPost(sender: String, group: MessageGroup) -> Bool {
        if sender == "SYSTEM" || sender.hasPrefix("bot:") { return true }
        guard group.settings?.postPolicy == .admins else { return true }
        return isAdmin(sender, in: group)
    }

    /// The local composer state for `me` (§2.4 sender UI, §2.9 dissolved).
    enum ComposerState: Equatable { case open, onlyAdmins, dissolved }
    nonisolated static func composerState(me: String?, group: MessageGroup) -> ComposerState {
        if group.settings?.dissolved == true { return .dissolved }
        if group.settings?.postPolicy == .admins && !isAdmin(me, in: group) { return .onlyAdmins }
        return .open
    }
}

// MARK: - Join decision (§3.3)

enum GroupJoinVerdict: Equatable {
    case accept
    case alreadyMember
    case refuse(String)
}

enum GroupJoinAuthorizer {
    static let maxAcceptsPerHour = 60

    /// `join_request` by handle. Returns the verdict and the group it targets.
    nonisolated static func authorizeHandleJoin(sender: String?,
                                                requester: String?,
                                                handle: String?,
                                                groups: [MessageGroup],
                                                me: String?,
                                                acceptsInLastHour: (UUID) -> Int) -> (GroupJoinVerdict, UUID?) {
        guard let sender, !sender.isEmpty else { return (.refuse("unauthenticated"), nil) }
        let s = MessageManager.canonicalIdentity(sender)
        guard let requester, MessageManager.canonicalIdentity(requester) == s else {
            return (.refuse("body-names-a-third-party"), nil)
        }
        guard let h = GroupSettings.normalizedHandle(handle),
              let group = groups.first(where: { $0.settings?.visibility == .public && $0.settings?.handle == h }) else {
            return (.refuse("unknown-handle"), nil)
        }
        guard GroupSettingsPolicy.isAdmin(me, in: group) else { return (.refuse("not-admin"), group.id) }
        if group.settings?.dissolved == true { return (.refuse("dissolved"), group.id) }
        if (group.evictedMemberKeys ?? []).contains(where: { MessageManager.canonicalIdentity($0) == s }) {
            return (.refuse("banned"), group.id)
        }
        if group.members.contains(where: { MessageManager.canonicalIdentity($0.publicKey) == s }) {
            return (.alreadyMember, group.id)
        }
        if acceptsInLastHour(group.id) >= maxAcceptsPerHour { return (.refuse("rate-limited"), group.id) }
        return (.accept, group.id)
    }

    /// `member_sync_request` (rules 1–3 of the shipped handler first, then §3.3 4–6).
    /// `mayAddMembers` = our `canAddMembers` for this group.
    nonisolated static func authorizeSyncRequest(sender: String?,
                                                 requester: String?,
                                                 token: String?,
                                                 group: MessageGroup,
                                                 mayAddMembers: Bool) -> GroupJoinVerdict {
        guard let sender, !sender.isEmpty else { return .refuse("unauthenticated") }
        let s = MessageManager.canonicalIdentity(sender)
        guard let requester, MessageManager.canonicalIdentity(requester) == s else {
            return .refuse("body-names-a-third-party")
        }
        if (group.evictedMemberKeys ?? []).contains(where: { MessageManager.canonicalIdentity($0) == s }) {
            return .refuse("banned")
        }
        if group.members.contains(where: { MessageManager.canonicalIdentity($0.publicKey) == s }) {
            return .alreadyMember
        }
        if group.settings?.dissolved == true { return .refuse("dissolved") }
        if let ours = group.settings?.inviteToken {
            guard GroupSettings.tokensMatch(ours, token), mayAddMembers else { return .refuse("invite-revoked") }
            return .accept
        }
        return mayAddMembers ? .accept : .refuse("may-not-add")
    }
}

/// In-memory sliding window for §3.3 rule 6 (per device, per group).
final class GroupJoinRateLimiter {
    static let shared = GroupJoinRateLimiter()
    private var accepts: [UUID: [Date]] = [:]
    private let lock = NSLock()

    func count(_ gid: UUID, now: Date = Date()) -> Int {
        lock.lock(); defer { lock.unlock() }
        let fresh = (accepts[gid] ?? []).filter { now.timeIntervalSince($0) < 3600 }
        accepts[gid] = fresh
        return fresh.count
    }

    func record(_ gid: UUID, now: Date = Date()) {
        lock.lock(); defer { lock.unlock() }
        accepts[gid, default: []].append(now)
    }
}

// MARK: - Recent events / admin log (§2.8)

struct GroupAdminEvent: Codable, Equatable, Identifiable {
    var id: UUID = UUID()
    var at: Date
    /// Authenticated sender, or our own key for local actions.
    var actor: String
    var kind: String
    /// Target member key (member/admin events) or free detail (new name, handle…).
    var detail: String?

    static func == (a: GroupAdminEvent, b: GroupAdminEvent) -> Bool {
        a.at == b.at && a.actor == b.actor && a.kind == b.kind && a.detail == b.detail
    }
}

enum GroupAdminLog {
    static let retention: TimeInterval = 48 * 3600
    static let cap = 500

    /// Diff two copies of the same group into events (pure).
    nonisolated static func diff(old: MessageGroup, new: MessageGroup, actor: String, at: Date = Date()) -> [GroupAdminEvent] {
        var out: [GroupAdminEvent] = []
        func add(_ kind: String, _ detail: String? = nil, actor a: String = actor) {
            out.append(GroupAdminEvent(at: at, actor: a, kind: kind, detail: detail))
        }
        let canon = MessageManager.canonicalIdentity
        if old.name != new.name { add("renamed", new.name) }
        if (old.groupDescription ?? "") != (new.groupDescription ?? "") { add("description_changed") }
        if new.groupPictureData != nil && old.groupPictureData != new.groupPictureData { add("photo_changed") }

        let oldKeys = Dictionary(old.members.map { (canon($0.publicKey), $0) }, uniquingKeysWith: { a, _ in a })
        let newKeys = Dictionary(new.members.map { (canon($0.publicKey), $0) }, uniquingKeysWith: { a, _ in a })
        let actorCanon = canon(actor)
        for m in new.members where oldKeys[canon(m.publicKey)] == nil { add("member_added", m.publicKey) }
        for m in old.members where newKeys[canon(m.publicKey)] == nil {
            if canon(m.publicKey) == actorCanon { add("member_left", nil) } else { add("member_removed", m.publicKey) }
        }
        for m in new.members {
            guard let before = oldKeys[canon(m.publicKey)] else { continue }
            if !before.isAdmin && m.isAdmin { add("admin_promoted", m.publicKey) }
            if before.isAdmin && !m.isAdmin { add("admin_demoted", m.publicKey) }
        }

        let a = old.settings ?? GroupSettings(), b = new.settings ?? GroupSettings()
        if a.visibility != b.visibility { add("visibility_changed", b.visibility.rawValue) }
        if a.handle != b.handle, b.handle != nil { add("handle_changed", b.handle) }
        if a.inviteToken != b.inviteToken, b.inviteToken != nil { add("invite_link_reset") }
        if a.reactions != b.reactions || a.allowedReactions != b.allowedReactions { add("reactions_changed") }
        if a.welcome != b.welcome { add("welcome_changed") }
        if a.postPolicy != b.postPolicy { add("post_policy_changed", b.postPolicy.rawValue) }
        if a.adminDMs != b.adminDMs { add("admin_dms_changed") }
        if a.comments != b.comments { add("comments_changed", b.comments ? "on" : "off") }
        if !a.dissolved && b.dissolved { add("dissolved") }
        return out
    }

    nonisolated static func pruned(_ events: [GroupAdminEvent], now: Date = Date()) -> [GroupAdminEvent] {
        let fresh = events.filter { now.timeIntervalSince($0.at) < retention }
        return fresh.count > cap ? Array(fresh.suffix(cap)) : fresh
    }
}

/// Sealed at rest (FileStorage → AtRestCipher): the log names people.
final class GroupAdminLogStore {
    static let shared = GroupAdminLogStore()
    private let key = "group_admin_log_v1"
    private var cache: [String: [GroupAdminEvent]]?
    private let lock = NSLock()

    private func loadLocked() -> [String: [GroupAdminEvent]] {
        if let cache { return cache }
        let loaded: [String: [GroupAdminEvent]] = FileStorage.shared.load(forKey: key) ?? [:]
        cache = loaded
        return loaded
    }

    func append(_ gid: UUID, _ events: [GroupAdminEvent]) {
        guard !events.isEmpty else { return }
        lock.lock(); defer { lock.unlock() }
        var all = loadLocked()
        all[gid.uuidString] = GroupAdminLog.pruned((all[gid.uuidString] ?? []) + events)
        cache = all
        FileStorage.shared.save(all, forKey: key)
    }

    func events(_ gid: UUID) -> [GroupAdminEvent] {
        lock.lock(); defer { lock.unlock() }
        return GroupAdminLog.pruned(loadLocked()[gid.uuidString] ?? []).reversed()
    }

    func forget(_ gid: UUID) {
        lock.lock(); defer { lock.unlock() }
        var all = loadLocked()
        all.removeValue(forKey: gid.uuidString)
        cache = all
        FileStorage.shared.save(all, forKey: key)
    }
}

// MARK: - Small local stores (welcome shown, pending stubs, auto-translate, unregister)

/// Per-device sets keyed by group id. Sealed like everything else that is
/// about which groups this person is in.
final class GroupLocalFlags {
    static let shared = GroupLocalFlags()
    private let key = "group_local_flags_v1"
    private struct State: Codable {
        var welcomeShown: [String] = []
        var pendingStubs: [String] = []
        var autoTranslate: [String] = []
        var pendingUnregister: [String] = []
    }
    private var state: State?
    private let lock = NSLock()

    private func mutate(_ f: (inout State) -> Void) {
        lock.lock(); defer { lock.unlock() }
        var s = state ?? (FileStorage.shared.load(forKey: key) ?? State())
        f(&s)
        state = s
        FileStorage.shared.save(s, forKey: key)
    }
    private func read() -> State {
        lock.lock(); defer { lock.unlock() }
        if let state { return state }
        let s: State = FileStorage.shared.load(forKey: key) ?? State()
        state = s
        return s
    }

    /// Returns true the FIRST time it is called for `gid` (§2.5: one per group per device).
    func markWelcomeShown(_ gid: UUID) -> Bool {
        var first = false
        mutate { s in
            if !s.welcomeShown.contains(gid.uuidString) { s.welcomeShown.append(gid.uuidString); first = true }
        }
        return first
    }

    func isPendingStub(_ gid: UUID) -> Bool { read().pendingStubs.contains(gid.uuidString) }
    func setPendingStub(_ gid: UUID, _ on: Bool) {
        mutate { s in
            s.pendingStubs.removeAll { $0 == gid.uuidString }
            if on { s.pendingStubs.append(gid.uuidString) }
        }
    }

    func autoTranslate(_ gid: UUID) -> Bool { read().autoTranslate.contains(gid.uuidString) }
    func setAutoTranslate(_ gid: UUID, _ on: Bool) {
        mutate { s in
            s.autoTranslate.removeAll { $0 == gid.uuidString }
            if on { s.autoTranslate.append(gid.uuidString) }
        }
    }

    /// Every per-group flag (dissolve, tests).
    func forgetGroup(_ gid: UUID) {
        mutate { s in
            for k in [\State.welcomeShown, \State.pendingStubs, \State.autoTranslate] {
                s[keyPath: k].removeAll { $0 == gid.uuidString }
            }
        }
    }

    var pendingUnregister: [String] { read().pendingUnregister }
    func setPendingUnregister(_ handle: String, _ on: Bool) {
        mutate { s in
            s.pendingUnregister.removeAll { $0 == handle }
            if on { s.pendingUnregister.append(handle) }
        }
    }
}

// MARK: - Public handle registry client (§4)

struct GroupHandleRecord: Decodable, Equatable {
    struct Preview: Decodable, Equatable {
        var name: String?
        var description: String?
        var memberCount: Int?
    }
    var handle: String
    var joinKeys: [String]
    var preview: Preview?
}

enum GroupHandleRegistry {
    enum PutResult: Equatable { case ok, taken, invalid, error }

    /// hex sha256("oshi-group-handle-v1:" + UPPER(gid)) (§4.1).
    nonisolated static func groupRef(_ gid: UUID) -> String {
        SHA256.hash(data: Data(("oshi-group-handle-v1:" + gid.uuidString.uppercased()).utf8))
            .map { String(format: "%02x", $0) }.joined()
    }

    /// Admins, creator first then the others (§4.3).
    nonisolated static func adminKeys(_ group: MessageGroup) -> [String] {
        let admins = group.members.filter { $0.isAdmin }.map { $0.publicKey }
        let creator = MessageManager.canonicalIdentity(group.adminPublicKey)
        return admins.sorted { a, b in
            (MessageManager.canonicalIdentity(a) == creator ? 0 : 1) < (MessageManager.canonicalIdentity(b) == creator ? 0 : 1)
        }
    }

    /// PUT body (§4.2): `controllers` = all admins (≤ 16), `joinKeys` = first 8.
    nonisolated static func putBody(_ group: MessageGroup, settings: GroupSettings) -> [String: Any] {
        let admins = adminKeys(group)
        var body: [String: Any] = [
            "groupRef": groupRef(group.id),
            "controllers": Array(admins.prefix(16)),
            "joinKeys": Array(admins.prefix(8)),
        ]
        if settings.showPreview {
            var p: [String: Any] = ["name": String(group.name.prefix(64)), "memberCount": group.members.count]
            if let d = group.groupDescription, !d.isEmpty { p["description"] = String(d.prefix(255)) }
            body["preview"] = p
        } else {
            body["preview"] = NSNull()
        }
        return body
    }

    static func lookup(_ handle: String) async throws -> GroupHandleRecord? {
        let (status, data) = try await V2Client().groupHandleRequest(method: "GET", handle: handle, body: nil)
        if status == 404 { return nil }
        guard (200...299).contains(status) else { throw URLError(.badServerResponse) }
        return try JSONDecoder().decode(GroupHandleRecord.self, from: data)
    }

    static func register(_ handle: String, group: MessageGroup, settings: GroupSettings) async -> PutResult {
        guard let body = try? JSONSerialization.data(withJSONObject: putBody(group, settings: settings)) else { return .error }
        guard let (status, _) = try? await V2Client().groupHandleRequest(method: "PUT", handle: handle, body: body) else { return .error }
        switch status {
        case 200...299: return .ok
        case 409: return .taken
        case 400, 403: return .invalid
        default: return .error
        }
    }

    /// DELETE; a failure is kept in the pending list and retried at next launch.
    @discardableResult
    static func unregister(_ handle: String) async -> Bool {
        GroupLocalFlags.shared.setPendingUnregister(handle, true)
        guard let (status, _) = try? await V2Client().groupHandleRequest(method: "DELETE", handle: handle, body: nil) else { return false }
        // 404 = already gone (or not ours): nothing left to retry.
        if (200...299).contains(status) || status == 404 {
            GroupLocalFlags.shared.setPendingUnregister(handle, false)
            return true
        }
        return false
    }

    static func retryPendingUnregisters() {
        let pending = GroupLocalFlags.shared.pendingUnregister
        guard !pending.isEmpty else { return }
        Task { for h in pending { await unregister(h) } }
    }

    /// Re-PUT at most once per 7 days while public (§4.2 expiry).
    static func refreshIfDue(_ group: MessageGroup, me: String?) {
        guard let s = group.settings, s.isPublic, let h = s.handle,
              GroupSettingsPolicy.isAdmin(me, in: group) else { return }
        let k = "oshi.groupHandle.lastPut.\(h)"
        let last = UserDefaults.standard.double(forKey: k)
        guard Date().timeIntervalSince1970 - last > 7 * 86400 else { return }
        Task {
            if await register(h, group: group, settings: s) == .ok {
                UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: k)
            }
        }
    }
}

// MARK: - Public link parsing (§3.1)

enum GroupPublicLink {
    /// `https://oshi-messenger.com/g/<h>` (+ .org, www.), `oshi://g/<h>`,
    /// `oshi://group/join?h=<h>`. Returns the raw (un-validated) handle, or nil when
    /// the URL is not a public-group link at all.
    static func rawHandle(from url: URL) -> String? {
        let parts = url.pathComponents.filter { $0 != "/" }
        if url.scheme == "oshi" {
            if url.host == "g" { return parts.first ?? "" }
            if url.host == "group",
               let h = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "h" })?.value {
                return h
            }
            return nil
        }
        guard url.scheme == "https", let host = url.host?.lowercased(),
              ["oshi-messenger.com", "www.oshi-messenger.com", "oshi-messenger.org", "www.oshi-messenger.org"].contains(host),
              parts.first == "g" else { return nil }
        return parts.count > 1 ? parts[1] : ""
    }
}
