//
//  BotPlatform.swift
//  OSHI — bots you can find, add and talk to INSIDE groups and channels.
//
//  __BOTS_PHASE1_2026_11_24__ Owner decision (24/11, evening): "like Telegram
//  with BotFather, very user friendly, and also for channels where there is
//  interaction inside. Not a bot chat." So there is NO private bot chat here:
//  a bot lives in groups and channels, where it posts (with buttons), edits its
//  own posts in place, and answers `/commands` and button taps.
//
//  This file is the non-UI half:
//    • the public identity of a bot — `@username` rules, links, public profile;
//    • `BotPlatformService`, the interface to the server routes of the phase-1
//      contract (profile, search, setMyProfile, revokeToken, commands, add,
//      command dispatch), its URLSession implementation, and nothing else —
//      the tests drive the real HTTP code through a fake transport;
//    • `GroupBotDirectory`: which REMOTE bots serve a group on this device,
//      their commands, and the `kind:"command"` dispatch of a typed `/cmd`;
//    • the in-place edit / delete of a bot's own post (`BotPostUpdate`);
//    • the BotFather helpers (templates, username suggestions, snippets).
//
//  Server shapes: see the contract's "Server amendments". Routes whose shape
//  was not settled when this was written are marked PENDING in their comment.
//

import Foundation
import CryptoKit

// MARK: - Username

/// `^[a-z][a-z0-9_]{2,28}bot$` — 6 to 32 characters, lowercase, ends with
/// `bot` (the contract's prose says 5–32, its regex — which the server
/// enforces — gives 6–32; the regex wins).
enum BotUsername {
    static let minLength = 6
    static let maxLength = 32

    enum Problem: Equatable {
        case empty, tooShort, tooLong, mustStartWithLetter, invalidCharacters, mustEndWithBot

        var messageKey: String {
            switch self {
            case .empty: return "bot.username.error.empty"
            case .tooShort: return "bot.username.error.too_short"
            case .tooLong: return "bot.username.error.too_long"
            case .mustStartWithLetter: return "bot.username.error.start_letter"
            case .invalidCharacters: return "bot.username.error.characters"
            case .mustEndWithBot: return "bot.username.error.end_bot"
            }
        }
    }

    /// `" @WeatherBot "` → `"weatherbot"`.
    static func normalize(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasPrefix("@") { s.removeFirst() }
        return s.lowercased()
    }

    /// The field's live filter: lowercase, spaces/dashes → `_`, anything else
    /// outside a-z0-9_ dropped, capped at 32.
    static func sanitizeWhileTyping(_ raw: String) -> String {
        var out = ""
        for ch in normalize(raw) {
            if ch == " " || ch == "-" { out.append("_") }
            else if ch.isASCII, ch.isLetter || ch.isNumber || ch == "_" { out.append(ch) }
            if out.count == maxLength { break }
        }
        return out
    }

    static func problem(_ raw: String) -> Problem? {
        let u = normalize(raw)
        if u.isEmpty { return .empty }
        let allowed = u.unicodeScalars.allSatisfy { ($0 >= "a" && $0 <= "z") || ($0 >= "0" && $0 <= "9") || $0 == "_" }
        if !allowed { return .invalidCharacters }
        if let first = u.unicodeScalars.first, !(first >= "a" && first <= "z") { return .mustStartWithLetter }
        if u.count > maxLength { return .tooLong }
        if !u.hasSuffix("bot") { return .mustEndWithBot }
        if u.count < minLength { return .tooShort }
        return nil
    }

    static func isValid(_ raw: String) -> Bool { problem(raw) == nil }

    /// Usernames offered for a bot name, best first, never one in `taken` and
    /// never an invalid one. `"Météo Paris"` → `meteo_parisbot`, `meteo_paris_bot`, …
    static func suggestions(forName name: String, taken: Set<String> = [], count: Int = 3) -> [String] {
        let folded = name.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: .init(identifier: "en"))
        var base = sanitizeWhileTyping(folded)
        while base.hasPrefix("_") || base.first?.isNumber == true { base.removeFirst() }
        while base.hasSuffix("_") { base.removeLast() }
        if base.hasSuffix("bot") { base = String(base.dropLast(3)) }
        while base.hasSuffix("_") { base.removeLast() }
        if base.isEmpty { base = "my" }
        if base.count < 3 { base += "oshi" }
        base = String(base.prefix(maxLength - 7))   // room for `_99_bot`

        var candidates = [base + "bot", base + "_bot", "the" + base + "bot"]
        for n in 1...99 { candidates.append("\(base)\(n)bot"); candidates.append("\(base)_\(n)_bot") }
        var out: [String] = []
        for c in candidates where isValid(c) && !taken.contains(c) && !out.contains(c) {
            out.append(c)
            if out.count == count { break }
        }
        return out
    }
}

// MARK: - Public id

enum BotPublicID {
    /// `sha256("oshi-bot-id:" + token)[:16]` in hex — the server's `botIdFor`.
    /// One-way: it names a bot without being able to act as it.
    static func forToken(_ token: String) -> String {
        SHA256.hash(data: Data(("oshi-bot-id:" + token).utf8))
            .map { String(format: "%02x", $0) }.joined().prefix(16).lowercased()
    }

    /// A stable local UUID for a public id (the `/` menu keys rows by UUID).
    static func localUUID(for botId: String) -> UUID {
        var bytes = Array(SHA256.hash(data: Data(("oshi-bot-row:" + botId).utf8)).prefix(16))
        bytes[6] = (bytes[6] & 0x0F) | 0x40
        bytes[8] = (bytes[8] & 0x3F) | 0x80
        return UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                           bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]))
    }
}

// MARK: - Links

/// `https://oshi-messenger.com/bot/<username>[?start=<payload>]` and
/// `oshi://bot/<username>[?start=…]`. A link opens the bot's PROFILE sheet
/// (with "Add to group" / "Add to channel"); the start payload is kept for the
/// server-side web page and is not used by the app in phase 1.
struct BotLink: Equatable {
    var username: String
    var startPayload: String?

    static let hosts: Set<String> = ["oshi-messenger.com", "www.oshi-messenger.com",
                                     "oshi-messenger.org", "www.oshi-messenger.org"]

    /// `nil` = not a bot link (the caller keeps routing). A bot link with a
    /// malformed username still parses (`username` as typed) so the sheet can
    /// say "this bot doesn't exist" instead of the link doing nothing.
    static func parse(_ url: URL) -> BotLink? {
        let raw: String?
        if url.scheme?.lowercased() == "oshi", url.host?.lowercased() == "bot" {
            raw = url.pathComponents.filter { $0 != "/" }.first
        } else if url.scheme?.lowercased() == "https", let host = url.host?.lowercased(), hosts.contains(host) {
            let parts = url.pathComponents.filter { $0 != "/" }
            guard parts.first?.lowercased() == "bot" else { return nil }
            raw = parts.count > 1 ? parts[1] : nil
        } else {
            return nil
        }
        let username = BotUsername.normalize(raw ?? "")
        let payload = URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "start" })?.value
        return BotLink(username: username, startPayload: payload.map(isValidPayload) == true ? payload : nil)
    }

    static func isValidPayload(_ p: String) -> Bool {
        guard (1...64).contains(p.count) else { return false }
        return p.unicodeScalars.allSatisfy {
            ($0 >= "a" && $0 <= "z") || ($0 >= "A" && $0 <= "Z") || ($0 >= "0" && $0 <= "9") || $0 == "_" || $0 == "-"
        }
    }

    static func url(username: String) -> URL? {
        URL(string: "https://oshi-messenger.com/bot/\(BotUsername.normalize(username))")
    }
}

// MARK: - Public profile

struct BotPublicProfile: Codable, Hashable, Identifiable {
    var botId: String
    var username: String
    var name: String
    var description: String
    var about: String
    var avatarEmoji: String
    var commands: [BotCommandSpec]
    var verified: Bool
    var hosting: String
    var isPublic: Bool

    var id: String { botId }
    var isRemote: Bool { hosting != "local" }

    static let maxAbout = 120
    static let maxDescription = 512

    static func parse(_ any: Any?) -> BotPublicProfile? {
        guard let d = any as? [String: Any],
              let botId = (d["botId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !botId.isEmpty else { return nil }
        let commands = (d["commands"] as? [[String: Any]] ?? []).compactMap { item -> BotCommandSpec? in
            let name = BotCommandSpec.normalizeName(item["command"] as? String ?? "")
            guard BotCommandSpec.problem(forName: name) == nil else { return nil }
            return BotCommandSpec(name: name, description: String((item["description"] as? String ?? "").prefix(BotCommandSpec.maxDescriptionLength)))
        }
        return BotPublicProfile(
            botId: botId,
            username: BotUsername.normalize(d["username"] as? String ?? ""),
            name: String((d["name"] as? String ?? "Bot").prefix(64)),
            description: String((d["description"] as? String ?? "").prefix(maxDescription)),
            about: String((d["about"] as? String ?? "").prefix(maxAbout)),
            avatarEmoji: String((d["avatarEmoji"] as? String ?? "").prefix(4)),
            commands: commands,
            verified: d["verified"] as? Bool ?? false,
            hosting: d["hosting"] as? String ?? "remote",
            isPublic: d["public"] as? Bool ?? false
        )
    }
}

/// What the owner changes with `setMyProfile`. `nil` = leave unchanged.
struct BotProfileUpdate: Equatable {
    var username: String?
    var name: String?
    var description: String?
    var about: String?
    var avatarEmoji: String?
    var isPublic: Bool?

    func body(token: String) -> [String: Any] {
        var b: [String: Any] = ["token": token]
        if let username { b["username"] = BotUsername.normalize(username) }
        if let name { b["name"] = name }
        if let description { b["description"] = String(description.prefix(BotPublicProfile.maxDescription)) }
        if let about { b["about"] = String(about.prefix(BotPublicProfile.maxAbout)) }
        if let avatarEmoji { b["avatarEmoji"] = avatarEmoji }
        if let isPublic { b["public"] = isPublic }
        return b
    }
}

/// What an adder grants a bot in a group or channel (Telegram's admin rights
/// for bots). Sent with the add; the server enforces it on edit/delete/pin.
struct BotGroupPermissions: Codable, Hashable {
    var canPost: Bool = true
    var canEdit: Bool = true
    var canDelete: Bool = false
    var canPin: Bool = false

    var wire: [String: Bool] {
        ["canPost": canPost, "canEdit": canEdit, "canDelete": canDelete, "canPin": canPin]
    }
}

struct BotAddRequest: Equatable {
    var botId: String
    var groupId: String
    /// `"group"` or `"channel"` — the bot's updates carry it as `chat.type`.
    var kind: String
    var members: [String]
    var permissions: BotGroupPermissions
    var adderPublicKey: String
}

// MARK: - Service

enum BotPlatformError: Error, Equatable {
    case notFound
    case usernameTaken
    case usernameInvalid
    case notMember
    case forbidden
    case signatureRequired
    case rateLimited
    case rejected(String)
    case http(Int)
    case malformed
    case noIdentity
}

/// The server routes of bots phase 1, as one interface. The app talks to
/// `HTTPBotPlatformService`; the tests to a fake (or the HTTP one over a
/// fake transport).
protocol BotPlatformService {
    func profile(username: String) async throws -> BotPublicProfile
    func search(_ query: String) async throws -> [BotPublicProfile]
    func setProfile(token: String, update: BotProfileUpdate) async throws -> BotPublicProfile?
    /// `(token, botId)` — the botId is STABLE across revokes (S3).
    func revokeToken(_ token: String) async throws -> (token: String, botId: String?)
    /// `nil` = available; else why not (S1 `usernameAvailable`). `token` lets the
    /// owner's own current username read as available.
    func usernameUnavailableReason(_ username: String, token: String?) async throws -> BotPlatformError?
    func publicCommands(botId: String, userPublicKey: String, groupId: String?) async throws -> [BotCommandSpec]
    func addBot(_ request: BotAddRequest) async throws
    func removeBot(botId: String, groupId: String, adderPublicKey: String) async throws
    func dispatchCommand(botId: String, groupId: String, text: String, fromPublicKey: String) async throws
}

struct HTTPBotPlatformService: BotPlatformService {
    typealias Transport = (URLRequest) async throws -> (Data, URLResponse)

    var baseURL = "https://oshi-messenger.com"
    var transport: Transport = { try await URLSession.shared.data(for: $0) }
    /// Ed25519 request signature (contract A1) — the key `/v2/*` is signed with.
    /// Returns false when no key is readable; the request then goes unsigned,
    /// which the routes that only accept a claimed key still take.
    var signer: (inout URLRequest) -> Bool = { CallPushSigner.sign(&$0) }

    // MARK: Requests (static so the tests assert the wire shape)

    func get(_ path: String, _ query: [String: String?], signed: Bool = false) -> URLRequest? {
        var comps = URLComponents(string: baseURL + path)
        comps?.queryItems = query.sorted { $0.key < $1.key }.compactMap { k, v in v.map { URLQueryItem(name: k, value: $0) } }
        // `+` is legal in a query but read as a space by most servers: a
        // base64 key would arrive corrupted.
        let encoded = comps?.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        comps?.percentEncodedQuery = encoded
        guard let url = comps?.url else { return nil }
        var r = URLRequest(url: url)
        r.timeoutInterval = 15
        if signed { _ = signer(&r) }
        return r
    }

    func post(_ path: String, _ body: [String: Any], signed: Bool = false) -> URLRequest? {
        guard let url = URL(string: baseURL + path),
              let data = try? JSONSerialization.data(withJSONObject: body, options: [.sortedKeys]) else { return nil }
        var r = URLRequest(url: url)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = data
        r.timeoutInterval = 15
        if signed { _ = signer(&r) }   // after the body: the signature covers it
        return r
    }

    /// Status + `error` string → the error a screen can say something about.
    static func error(status: Int, data: Data) -> BotPlatformError? {
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        let code = (json?["error"] as? String) ?? ""
        if (200..<300).contains(status) {
            if let ok = json?["success"] as? Bool, !ok { return code.isEmpty ? .rejected("") : map(code, status: status) }
            return nil
        }
        return map(code, status: status)
    }

    private static func map(_ code: String, status: Int) -> BotPlatformError {
        switch code {
        case "username_taken": return .usernameTaken
        case "username_invalid": return .usernameInvalid
        case "not_found": return .notFound
        case "not_member", "adder_not_member", "not_adder": return .notMember
        case "forbidden": return .forbidden
        case "signature_required": return .signatureRequired
        case "rate_limited": return .rateLimited
        default: break
        }
        switch status {
        case 404: return .notFound
        case 409: return .usernameTaken
        case 403: return .forbidden
        case 401: return .signatureRequired
        case 429: return .rateLimited
        default: return code.isEmpty ? .http(status) : .rejected(code)
        }
    }

    private func send(_ request: URLRequest?) async throws -> [String: Any] {
        guard var request else { throw BotPlatformError.malformed }
        var (data, response) = try await transport(request)
        // A signed request refused as `401 signature_required` (clock skew, key
        // rotated) is signed again and retried ONCE; a 403 is never looped on.
        if (response as? HTTPURLResponse)?.statusCode == 401, request.value(forHTTPHeaderField: "x-oshi-signature") != nil {
            request.setValue(nil, forHTTPHeaderField: "x-oshi-signature")
            if signer(&request) { (data, response) = try await transport(request) }
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if let e = Self.error(status: status, data: data) { throw e }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    // MARK: Routes

    func profile(username: String) async throws -> BotPublicProfile {
        let u = BotUsername.normalize(username)
        guard BotUsername.isValid(u) else { throw BotPlatformError.notFound }
        let json = try await send(get("/api/bot/public", ["username": u]))
        guard let p = BotPublicProfile.parse(json["bot"]) else { throw BotPlatformError.malformed }
        return p
    }

    func search(_ query: String) async throws -> [BotPublicProfile] {
        var q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        while q.hasPrefix("@") { q.removeFirst() }
        q = String(q.prefix(32))
        guard !q.isEmpty else { return [] }
        let json = try await send(get("/api/bot/search", ["q": q]))
        return (json["bots"] as? [Any] ?? []).compactMap(BotPublicProfile.parse)
    }

    func setProfile(token: String, update: BotProfileUpdate) async throws -> BotPublicProfile? {
        let json = try await send(post("/api/bot/setMyProfile", update.body(token: token)))
        return BotPublicProfile.parse(json["bot"])
    }

    /// S3: `POST /api/bot/revokeToken {token}` → `{success, token, botId}`.
    func revokeToken(_ token: String) async throws -> (token: String, botId: String?) {
        let json = try await send(post("/api/bot/revokeToken", ["token": token]))
        guard let fresh = json["token"] as? String, !fresh.isEmpty, fresh != token else { throw BotPlatformError.malformed }
        return (fresh, (json["botId"] as? String).flatMap { $0.isEmpty ? nil : $0.lowercased() })
    }

    /// S1: `GET /api/bot/usernameAvailable?username=&token=` → `{success, available, reason?}`.
    func usernameUnavailableReason(_ username: String, token: String?) async throws -> BotPlatformError? {
        let u = BotUsername.normalize(username)
        guard BotUsername.isValid(u) else { return .usernameInvalid }
        let json = try await send(get("/api/bot/usernameAvailable", ["username": u, "token": token]))
        if json["available"] as? Bool == true { return nil }
        return (json["reason"] as? String) == "invalid" ? .usernameInvalid : .usernameTaken
    }

    func publicCommands(botId: String, userPublicKey: String, groupId: String?) async throws -> [BotCommandSpec] {
        let json = try await send(get("/api/bot/commands",
                                      ["botId": botId, "userPublicKey": userPublicKey, "groupId": groupId],
                                      signed: true))
        guard let data = try? JSONSerialization.data(withJSONObject: json) else { return [] }
        return try HTTPBotCommandsService.parse(data)
    }

    /// S4: `POST /api/bot/addToGroup {botId, groupId, kind, members,
    /// permissions, adderPublicKey}` (signed; no group name — the server does
    /// not need it). For a bot this device OWNS, `/api/bot/register` carries
    /// the same `kind` + `permissions` per group instead (see BotManager).
    func addBot(_ request: BotAddRequest) async throws {
        _ = try await send(post("/api/bot/addToGroup", [
            "botId": request.botId,
            "groupId": request.groupId,
            "kind": request.kind,
            "members": request.members,
            "permissions": request.permissions.wire,
            "adderPublicKey": request.adderPublicKey,
        ], signed: true))
    }

    /// S4: `POST /api/bot/removeFromGroup {botId, groupId, adderPublicKey}` (signed).
    func removeBot(botId: String, groupId: String, adderPublicKey: String) async throws {
        _ = try await send(post("/api/bot/removeFromGroup", [
            "botId": botId, "groupId": groupId, "adderPublicKey": adderPublicKey,
        ], signed: true))
    }

    /// `POST /api/bot/dispatch {kind:"command", botId, groupId, text, fromPublicKey}`
    /// — the existing dispatch auth, signed as A1 recommends.
    func dispatchCommand(botId: String, groupId: String, text: String, fromPublicKey: String) async throws {
        _ = try await send(post("/api/bot/dispatch", [
            "kind": "command",
            "botId": botId,
            "groupId": groupId,
            "text": text,
            "fromPublicKey": fromPublicKey,
        ], signed: true))
    }
}

// MARK: - Commands typed in a group

/// Which remote bots a typed `/cmd` goes to. Pure.
enum BotGroupCommandRouter {
    struct Parsed: Equatable {
        var command: String
        var username: String?
        /// What is sent: the FIRST line only, ≤ 4096 (contract A8) — never the thread.
        var line: String
    }

    static func parse(_ text: String) -> Parsed? {
        let first = text.split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
        let line = first.trimmingCharacters(in: .whitespaces)
        guard line.hasPrefix("/") else { return nil }
        let token = String(line.dropFirst().prefix { !$0.isWhitespace })
        let pieces = token.split(separator: "@", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
        let name = BotCommandSpec.normalizeName(pieces.first ?? "")
        guard BotCommandSpec.problem(forName: name) == nil else { return nil }
        var username: String?
        if pieces.count == 2 {
            let u = BotUsername.normalize(pieces[1])
            guard !u.isEmpty else { return nil }
            username = u
        }
        return Parsed(command: name, username: username, line: String(line.prefix(4096)))
    }

    /// `/cmd@name` → that bot only (whatever its list says, as Telegram does);
    /// `/cmd` → every bot whose command list has it.
    static func targets(for text: String, bots: [GroupRemoteBot]) -> [String] {
        guard let p = parse(text) else { return [] }
        if let u = p.username {
            return bots.filter { $0.username == u }.map(\.botId)
        }
        return bots.filter { $0.commands.contains { $0.name == p.command } }.map(\.botId)
    }
}

/// A remote bot that serves a group, as this device knows it.
struct GroupRemoteBot: Codable, Hashable, Identifiable {
    var botId: String
    var name: String
    var username: String?
    var avatarEmoji: String?
    var commands: [BotCommandSpec] = []
    var commandsFetchedAt: Date?
    var permissions: BotGroupPermissions?

    var id: String { botId }
}

// MARK: - Directory of the group's remote bots

/// The remote bots of each group, on this device: the ones someone here added,
/// and the ones whose posts arrived (every bot envelope carries its public
/// `botId`). Feeds the `/` menu (their commands) and the dispatch of a typed
/// `/cmd`. Bots CREATED on this device are not stored here — they are in
/// `BotManager.bots`, and `routableBots` folds the remote ones in.
@MainActor
final class GroupBotDirectory: ObservableObject {
    static let shared = GroupBotDirectory()

    @Published private(set) var entries: [String: [GroupRemoteBot]] = [:]

    var service: BotPlatformService = HTTPBotPlatformService()
    var callerPublicKey: () -> String? = {
        if let key = MessageGroupManager.shared.currentUserPublicKey, !key.isEmpty { return key }
        if let key = MessageManager.sharedIdentityManager?.publicKey, !key.isEmpty { return key }
        return nil
    }
    private let defaults: UserDefaults
    private let storageKey = "oshi_group_bot_directory_v1"
    static let commandsTTL: TimeInterval = 600

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: storageKey),
           let decoded = try? JSONDecoder().decode([String: [GroupRemoteBot]].self, from: data) {
            entries = decoded
        }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(entries) { defaults.set(data, forKey: storageKey) }
    }

    private func key(_ groupId: UUID) -> String { groupId.uuidString.uppercased() }

    func bots(in groupId: UUID) -> [GroupRemoteBot] { entries[key(groupId)] ?? [] }

    /// A bot post arrived: remember that this bot serves this group.
    func note(botId rawId: String, name: String, groupId: UUID) {
        let botId = rawId.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !botId.isEmpty else { return }
        var list = bots(in: groupId)
        if let i = list.firstIndex(where: { $0.botId == botId }) {
            guard list[i].name != name, !name.isEmpty else { return }
            list[i].name = name
        } else {
            list.append(GroupRemoteBot(botId: botId, name: name.isEmpty ? "Bot" : name))
        }
        entries[key(groupId)] = list
        save()
    }

    /// Someone on this device added a bot found by link or search.
    func record(_ profile: BotPublicProfile, in groupId: UUID, permissions: BotGroupPermissions) {
        var list = bots(in: groupId).filter { $0.botId != profile.botId }
        list.append(GroupRemoteBot(botId: profile.botId, name: profile.name, username: profile.username,
                                   avatarEmoji: profile.avatarEmoji, commands: profile.commands,
                                   commandsFetchedAt: Date(), permissions: permissions))
        entries[key(groupId)] = list
        save()
    }

    func remove(botId: String, from groupId: UUID) {
        entries[key(groupId)] = bots(in: groupId).filter { $0.botId != botId }
        save()
    }

    /// Every remote bot a `/cmd` typed in this group may go to: the directory,
    /// plus the REMOTE bots created on this device and assigned to the group
    /// (their commands are local triggers). Local bots run on the device and
    /// already see the message, so they are never dispatched to.
    func routableBots(in groupId: UUID, owned: [OSHIBot]) -> [GroupRemoteBot] {
        var out = bots(in: groupId)
        for bot in owned where bot.assignedGroups.contains(groupId) && bot.isEnabled && BotManager.needsServerRegistration(bot) {
            let id = bot.publicId
            out.removeAll { $0.botId == id }
            out.append(GroupRemoteBot(botId: id, name: bot.name,
                                      username: bot.publishedUsername ?? BotUsername.normalize(bot.username),
                                      avatarEmoji: bot.avatarEmoji,
                                      commands: BotCommandList.commands(of: bot)))
        }
        return out
    }

    /// The `/` menu rows of the directory bots (owned bots are listed from
    /// their triggers by `SlashCommands`).
    func slashCommands(in groupId: UUID, owned: [OSHIBot]) -> [SlashCommand] {
        let ownedIds = Set(owned.map(\.publicId))
        return bots(in: groupId).filter { !ownedIds.contains($0.botId) }.flatMap { bot in
            bot.commands.map {
                SlashCommand(command: $0.slashed, description: $0.description, botName: bot.name,
                             botEmoji: bot.avatarEmoji ?? "🤖", botID: BotPublicID.localUUID(for: bot.botId),
                             sendsOnPick: !SlashCommands.takesArguments($0.description))
            }
        }
    }

    /// Fetch the command lists that are missing or stale (`/api/bot/commands`).
    func refreshCommands(in groupId: UUID) async {
        guard let me = callerPublicKey() else { return }
        let now = Date()
        for bot in bots(in: groupId) where (bot.commandsFetchedAt.map { now.timeIntervalSince($0) > Self.commandsTTL } ?? true) {
            do {
                let commands = try await service.publicCommands(botId: bot.botId, userPublicKey: me, groupId: groupId.uuidString)
                var list = bots(in: groupId)
                if let i = list.firstIndex(where: { $0.botId == bot.botId }) {
                    list[i].commands = commands
                    list[i].commandsFetchedAt = now
                    entries[key(groupId)] = list
                    save()
                }
            } catch {
                OshiLog.ai.info("⚠️ bot commands \(bot.botId.prefix(6)) failed: \(String(describing: error))")
                if error as? BotPlatformError == .notFound { remove(botId: bot.botId, from: groupId) }
            }
        }
    }

    /// After a text was SENT in a group: post `kind:"command"` to each remote
    /// bot the router picks. Returns the bot ids (the tests read them).
    @discardableResult
    func dispatchIfCommand(_ text: String, groupId: UUID, owned: [OSHIBot]) -> [String] {
        guard let parsed = BotGroupCommandRouter.parse(text) else { return [] }
        let targets = BotGroupCommandRouter.targets(for: text, bots: routableBots(in: groupId, owned: owned))
        guard !targets.isEmpty, let me = callerPublicKey() else { return [] }
        let service = self.service
        for botId in targets {
            Task {
                do {
                    try await service.dispatchCommand(botId: botId, groupId: groupId.uuidString,
                                                      text: parsed.line, fromPublicKey: me)
                } catch {
                    OshiLog.ai.info("⚠️ command dispatch to \(botId.prefix(6)) failed: \(String(describing: error))")
                }
            }
        }
        return targets
    }
}

// MARK: - A bot editing / deleting / pinning its own post

/// Contract S5: a sealed queue item of type `bot_message_action` — the bot
/// changed a post it made (live vote counts, "Registered ✓"), deleted it, or
/// (un)pinned it. INNER `{type:"bot_message_action", action:"edit"|"delete"|
/// "pin"|"unpin", messageId:<action envelope id>, targetMessageId:<original
/// post's messageId>, groupId, botId, content?, replyMarkup?}`. For `edit`:
/// `content` present = new text; `replyMarkup` key present = new keyboard
/// (null = remove); key absent = unchanged.
struct BotPostUpdate: Equatable {
    enum Kind: Equatable {
        /// `text == nil` keeps the text; `keyboard == .some(nil)` removes the buttons.
        case edit(text: String?, keyboard: BotInlineKeyboard??)
        case delete
        case pin
        case unpin
    }
    var kind: Kind
    var groupId: UUID
    /// The ORIGINAL post's server id (`targetMessageId`).
    var messageId: String
    var botId: String

    static let type = "bot_message_action"

    /// `outerBotId`: the botId of the OUTER envelope; when given, the inner one
    /// must match it (S5: "whose botId == outer botId, else drop it").
    static func parse(_ json: [String: Any], outerBotId: String? = nil) -> BotPostUpdate? {
        guard json["type"] as? String == type,
              let gid = json["groupId"] as? String, let groupId = UUID(uuidString: gid),
              let target = json["targetMessageId"] as? String, !target.isEmpty,
              let botId = (json["botId"] as? String)?.lowercased(), !botId.isEmpty else { return nil }
        if let outer = outerBotId?.lowercased(), !outer.isEmpty, outer != botId { return nil }
        let kind: Kind
        switch json["action"] as? String {
        case "delete": kind = .delete
        case "pin": kind = .pin
        case "unpin": kind = .unpin
        case "edit":
            let text = (json["content"] as? String) ?? (json["text"] as? String)
            let markupKey = ["replyMarkup", "reply_markup"].first { json.keys.contains($0) }
            let keyboard: BotInlineKeyboard?? = markupKey.map { BotInlineKeyboard.parse(json[$0]) }
            guard text != nil || keyboard != nil else { return nil }
            kind = .edit(text: text, keyboard: keyboard)
        default:
            return nil
        }
        return BotPostUpdate(kind: kind, groupId: groupId, messageId: target, botId: botId)
    }

    /// The local id of a bot post: the server id when it is a UUID, else a
    /// stable UUID derived from it — so a later action names the same row.
    static func localId(forServerId id: String) -> UUID {
        if let u = UUID(uuidString: id) { return u }
        return BotPublicID.localUUID(for: "msg:" + id)
    }

    /// Only the bot that wrote a post may change it: the stored post must be a
    /// bot post, and the botId recorded when it arrived must be the action's.
    static func isAuthor(senderKey: String, recordedBotId: String?, update: BotPostUpdate) -> Bool {
        guard senderKey.hasPrefix("bot:"), let recordedBotId, !recordedBotId.isEmpty else { return false }
        return recordedBotId.lowercased() == update.botId
    }
}

/// Which bot wrote which post (local message id → botId), recorded when the
/// post arrives — the only proof an action can be checked against. Bounded.
enum BotPostAuthors {
    private static let key = "oshi_bot_post_authors_v1"
    static func record(botId: String, localId: UUID, defaults: UserDefaults = .standard, cap: Int = 3000) {
        let id = botId.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !id.isEmpty else { return }
        var map = defaults.dictionary(forKey: key) as? [String: String] ?? [:]
        var order = defaults.stringArray(forKey: key + ".order") ?? []
        if map[localId.uuidString] == nil { order.append(localId.uuidString) }
        map[localId.uuidString] = id
        if order.count > cap {
            for old in order.prefix(order.count - cap) { map.removeValue(forKey: old) }
            order.removeFirst(order.count - cap)
        }
        defaults.set(map, forKey: key)
        defaults.set(order, forKey: key + ".order")
    }

    static func botId(for localId: UUID, defaults: UserDefaults = .standard) -> String? {
        (defaults.dictionary(forKey: key) as? [String: String])?[localId.uuidString]
    }
}

// MARK: - BotFather helpers

/// The kinds of bot BotFather offers, each with its starter commands.
enum BotFatherTemplate: String, CaseIterable, Identifiable {
    case poll, welcome, reminders, feed, custom
    var id: String { rawValue }

    var emoji: String {
        switch self {
        case .poll: return "🗳️"
        case .welcome: return "👋"
        case .reminders: return "⏰"
        case .feed: return "📰"
        case .custom: return "🛠️"
        }
    }

    var titleKey: String { "botfather.template.\(rawValue)" }
    var detailKey: String { "botfather.template.\(rawValue).detail" }

    /// Commands as (name, localization key of the description).
    var commandKeys: [(String, String)] {
        switch self {
        case .poll: return [("poll", "botfather.cmd.poll"), ("results", "botfather.cmd.results")]
        case .welcome: return [("rules", "botfather.cmd.rules"), ("about", "botfather.cmd.about")]
        case .reminders: return [("remind", "botfather.cmd.remind"), ("list", "botfather.cmd.list")]
        case .feed: return [("subscribe", "botfather.cmd.subscribe"), ("latest", "botfather.cmd.latest")]
        case .custom: return []
        }
    }
}

/// The ready-to-copy "How to connect" snippets, with the real API base URL.
enum BotConnectSnippets {
    static let baseURL = "https://oshi-messenger.com"

    static func curl(token: String, groupId: String?) -> String {
        """
        curl -X POST \(baseURL)/api/bot/send \\
          -H "Content-Type: application/json" \\
          -d '{"token":"\(token)","groupId":"\(groupId ?? "<group-id>")","content":"Hello!"}'
        """
    }

    static func python(token: String, groupId: String?) -> String {
        """
        import requests, time
        API = "\(baseURL)/api/bot"
        TOKEN = "\(token)"

        # Post in a group or channel the bot was added to
        requests.post(f"{API}/send", json={"token": TOKEN,
            "groupId": "\(groupId ?? "<group-id>")", "content": "Hello!"})

        # Receive /commands and button taps
        offset = 0
        while True:
            r = requests.get(f"{API}/getUpdates", params={"token": TOKEN,
                "offset": offset, "timeout": 25}).json()
            for u in r.get("result", []):
                offset = u["update_id"] + 1
                print(u)
        """
    }
}

// MARK: - Owner actions on a bot created on this device

extension OSHIBot {
    /// The public id. STABLE across token revokes (contract S3): the stored
    /// one wins; deriving from the token is only right for a never-revoked bot.
    var publicId: String { publicBotId ?? BotPublicID.forToken(token) }
}

extension BotManager {

    /// Add one of MY bots to a group/channel as an admin with `permissions`.
    /// The rights travel with the next `/api/bot/register` (per group).
    func addOwnedBot(_ botId: UUID, to groupId: UUID, permissions: BotGroupPermissions) {
        guard var bot = bots.first(where: { $0.id == botId }) else { return }
        if !bot.assignedGroups.contains(groupId) { bot.assignedGroups.append(groupId) }
        var rights = bot.groupPermissions ?? [:]
        rights[groupId.uuidString] = permissions
        bot.groupPermissions = rights
        updateBot(bot)
        registerBotWithServer(bot)
    }

    /// BotFather's /revoke: the server issues a new token, the old one stops
    /// working at once. The bot keeps its id, name, commands and groups.
    @discardableResult
    func revokeToken(of botId: UUID, service: BotPlatformService = HTTPBotPlatformService()) async throws -> OSHIBot {
        guard var bot = bots.first(where: { $0.id == botId }) else { throw BotPlatformError.notFound }
        let old = bot.publicId
        let fresh = try await service.revokeToken(bot.token)
        bot.publicBotId = fresh.botId ?? old     // stable (S3): never re-derived
        bot.token = fresh.token
        updateBot(bot)
        return bot
    }

    /// Push name / about / description / emoji / public / @username to the
    /// server (`setMyProfile`, in place). On success the accepted username is
    /// remembered as the published one.
    func publishProfile(of botId: UUID, update: BotProfileUpdate,
                        service: BotPlatformService = HTTPBotPlatformService()) async throws {
        guard let bot = bots.first(where: { $0.id == botId }) else { throw BotPlatformError.notFound }
        let echoed = try await service.setProfile(token: bot.token, update: update)
        guard var current = bots.first(where: { $0.id == botId }) else { return }
        if let u = update.username {
            current.publishedUsername = u.isEmpty ? nil : (echoed?.username.isEmpty == false ? echoed?.username : BotUsername.normalize(u))
            if !u.isEmpty { current.username = BotUsername.normalize(u) }
        }
        if let p = update.isPublic { current.isPublic = p }
        if let a = update.about { current.about = a }
        updateBot(current)
    }
}

extension BotLink: Identifiable {
    var id: String { username + "?" + (startPayload ?? "") }
}
