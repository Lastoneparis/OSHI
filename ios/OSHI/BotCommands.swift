//
//  BotCommands.swift
//  OSHI — a bot's command list, as Telegram has it: `/name` + a short line.
//
//  __BOT_COMMANDS_2026_11_24__ The `/` menu already existed (SlashCommands.swift,
//  08/26), but every command of a bot was described by the BOT's description,
//  the owner could not name or describe a command in the app, and the list the
//  server keeps for a remote bot (`setMyCommands`) was never written by any
//  client. This file is the shared model and the pure rules; the screens are in
//  BotCommandsEditorView.swift.
//
//  The model is the one Android and desktop use, and the one the server
//  enforces in `POST /api/bot/setMyCommands`:
//    • name  — lowercase a-z, 0-9 and `_`, 1…32 characters, stored WITHOUT `/`;
//    • description — at most 256 characters (the server truncates, we refuse);
//    • at most 100 commands per bot.
//
//  Storage: a command is a `.command` `BotTrigger` (that is what the local
//  runtime already matches against), and its description is the new optional
//  `BotTrigger.commandDescription`. Optional + synthesized Codable = a bot saved
//  by an older build decodes unchanged, and a trigger without a description
//  encodes byte-identical to before.
//

import Foundation

// MARK: - The command

struct BotCommandSpec: Codable, Hashable, Identifiable {
    /// Without the slash, already normalised (`help`, `new_game`).
    var name: String
    var description: String

    var id: String { name }
    /// What the composer gets and what the bot receives: `/help`.
    var slashed: String { "/" + name }

    static let maxNameLength = 32
    static let maxDescriptionLength = 256
    static let maxCount = 100

    enum Problem: Error, Equatable {
        case empty
        case tooLong
        case invalidCharacters
        case duplicate
        case descriptionTooLong
        case tooMany

        /// Localization key of the sentence shown under the field.
        var messageKey: String {
            switch self {
            case .empty: return "bot.commands.error.empty"
            case .tooLong: return "bot.commands.error.too_long"
            case .invalidCharacters: return "bot.commands.error.characters"
            case .duplicate: return "bot.commands.error.duplicate"
            case .descriptionTooLong: return "bot.commands.error.description_too_long"
            case .tooMany: return "bot.commands.error.too_many"
            }
        }
    }

    /// `" /Help "` → `"help"`. Lowercasing is not a correction the user has to
    /// make: the server only accepts lowercase, so we do it for them.
    static func normalizeName(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasPrefix("/") { s.removeFirst() }
        return s.lowercased()
    }

    /// The field's live filter, applied on every keystroke so that the user
    /// can hardly type an invalid name: lowercase, spaces and dashes become
    /// `_`, anything else outside a-z0-9_ is dropped, capped at 32.
    /// (Accented letters are dropped rather than transliterated — `café` →
    /// `caf` is visible at once, a silent `cafe` would be a guess.)
    static func sanitizeWhileTyping(_ raw: String) -> String {
        var out = ""
        for ch in normalizeName(raw) {
            if ch == " " || ch == "-" { out.append("_") }
            else if ch.isASCII, ch.isLetter || ch.isNumber || ch == "_" { out.append(ch) }
            if out.count == maxNameLength { break }
        }
        return out
    }

    static func problem(forName raw: String) -> Problem? {
        let name = normalizeName(raw)
        if name.isEmpty { return .empty }
        if name.count > maxNameLength { return .tooLong }
        let allowed = name.unicodeScalars.allSatisfy { s in
            (s >= "a" && s <= "z") || (s >= "0" && s <= "9") || s == "_"
        }
        return allowed ? nil : .invalidCharacters
    }

    static func problem(forDescription description: String) -> Problem? {
        description.count > maxDescriptionLength ? .descriptionTooLong : nil
    }
}

// MARK: - Commands <-> triggers

enum BotCommandList {

    /// The bot's commands, in the order the owner arranged them. Inactive
    /// triggers are kept out (they do not answer, so they must not be offered),
    /// a duplicate name keeps its first occurrence, and a condition that is not
    /// a valid name (`/foo bar`, written before validation existed) is skipped
    /// rather than "repaired" into a command that would not match at runtime.
    static func commands(of bot: OSHIBot) -> [BotCommandSpec] {
        var seen = Set<String>()
        var out: [BotCommandSpec] = []
        for t in bot.triggers where t.type == .command && t.isActive {
            let name = BotCommandSpec.normalizeName(t.condition)
            guard BotCommandSpec.problem(forName: name) == nil, seen.insert(name).inserted else { continue }
            out.append(BotCommandSpec(name: name, description: describe(t, in: bot)))
        }
        return out
    }

    /// The trigger's own description, else the bot's — never an empty row when
    /// something better is known.
    static func describe(_ trigger: BotTrigger, in bot: OSHIBot) -> String {
        let own = (trigger.commandDescription ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return own.isEmpty ? bot.description : own
    }

    /// Add (`editing == nil`) or change one command in `triggers`, leaving every
    /// non-command trigger exactly where it was. Pure: the editor screen and the
    /// tests go through the same function.
    static func upsert(_ triggers: [BotTrigger],
                       editing triggerID: UUID?,
                       name rawName: String,
                       description rawDescription: String,
                       reply: String) -> Result<[BotTrigger], BotCommandSpec.Problem> {
        let name = BotCommandSpec.normalizeName(rawName)
        if let p = BotCommandSpec.problem(forName: name) { return .failure(p) }
        let description = rawDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        if let p = BotCommandSpec.problem(forDescription: description) { return .failure(p) }

        let clash = triggers.contains { t in
            t.type == .command && t.id != triggerID && BotCommandSpec.normalizeName(t.condition) == name
        }
        if clash { return .failure(.duplicate) }

        var out = triggers
        if let triggerID, let idx = out.firstIndex(where: { $0.id == triggerID }) {
            out[idx].condition = "/" + name
            out[idx].commandDescription = description.isEmpty ? nil : description
            out[idx].responseTemplate = reply
            return .success(out)
        }
        let count = triggers.filter { $0.type == .command }.count
        if count >= BotCommandSpec.maxCount { return .failure(.tooMany) }
        out.append(BotTrigger(id: UUID(), type: .command, condition: "/" + name,
                              responseTemplate: reply, isActive: true,
                              commandDescription: description.isEmpty ? nil : description))
        return .success(out)
    }

    /// A couple of one-tap starters for the empty state. `/start` and `/help`
    /// are the two every Telegram user already expects to exist.
    static func starters(existing: [BotCommandSpec]) -> [String] {
        let have = Set(existing.map(\.name))
        return ["start", "help"].filter { !have.contains($0) }
    }
}

// MARK: - The `/` menu, as the composer shows it

/// What the bar above the composer shows for the current text, including the
/// friendly lines a bare `SlashCommands.matches` cannot express (it returns
/// `[]`, which means "no bar").
struct SlashMenuContent: Equatable {
    enum Hint: Equatable {
        /// The chat has bots, none of them has a command yet.
        case botsHaveNoCommands(botName: String, botID: UUID)
        /// Something after `/` was typed and nothing starts with it.
        case noMatch(query: String)
    }
    var commands: [SlashCommand]
    var hint: Hint?

    /// `nil` = no bar at all. In a chat WITHOUT bots this is exactly
    /// `SlashCommands.matches` (no new rows appear in ordinary chats); in a chat
    /// with bots the bar also says why it is empty instead of vanishing.
    static func make(for text: String, bots: [OSHIBot], extra: [SlashCommand] = []) -> SlashMenuContent? {
        guard let q = SlashCommands.query(in: text) else { return nil }
        let matches = SlashCommands.matches(for: text, bots: bots, extra: extra)
        let enabled = bots.filter(\.isEnabled)
        let builtInIDs = Set(SlashCommands.builtIns().map(\.botID))
        let anyBotCommand = !extra.isEmpty
            || SlashCommands.commands(from: enabled).contains { !builtInIDs.contains($0.botID) }

        var hint: Hint?
        if let first = enabled.first, !anyBotCommand, q.isEmpty {
            hint = .botsHaveNoCommands(botName: first.name, botID: first.id)
        }
        if matches.isEmpty {
            guard !enabled.isEmpty || !extra.isEmpty else { return nil }
            return SlashMenuContent(commands: [], hint: .noMatch(query: q))
        }
        return SlashMenuContent(commands: matches, hint: hint)
    }
}

/// Hardware-keyboard navigation in the `/` menu (iPad, Mac): ↑/↓ move a
/// highlight that wraps, Return/Tab act on it. `nil` = nothing highlighted
/// yet; Return then takes the first row, as Telegram Desktop does.
enum SlashMenuNavigation {
    static func move(_ current: Int?, by delta: Int, count: Int) -> Int? {
        guard count > 0 else { return nil }
        guard let current, current < count else { return delta >= 0 ? 0 : count - 1 }
        return ((current + delta) % count + count) % count
    }

    static func target(_ current: Int?, count: Int) -> Int? {
        guard count > 0 else { return nil }
        if let current, current < count { return current }
        return 0
    }
}

// MARK: - Server side of a command list

/// The two command endpoints that EXIST on the OSHI bot platform today
/// (bot_platform.js: `POST /api/bot/setMyCommands`, `GET /api/bot/getMyCommands`).
/// Both are authenticated by the bot TOKEN, so only the owner's device can use
/// them — which is exactly the editor's situation.
protocol BotCommandsService {
    func setCommands(_ commands: [BotCommandSpec], token: String) async throws
    func commands(token: String) async throws -> [BotCommandSpec]
}

/// __BOTS_PHASE1_2026_11_24__ The member-side command list (`GET /api/bot/commands`)
/// and `setMyProfile` moved to `BotPlatformService` (BotPlatform.swift).
enum BotCommandsServiceError: Error, Equatable {
    case http(Int)
    case rejected(String)
    case malformed
}

struct HTTPBotCommandsService: BotCommandsService {
    var baseURL = "https://oshi-messenger.com"
    var session: URLSession = .shared

    /// Wire shape, as the server parses it: `command` without the slash.
    static func body(_ commands: [BotCommandSpec], token: String) -> [String: Any] {
        ["token": token,
         "commands": commands.map { ["command": $0.name, "description": $0.description] }]
    }

    /// `{success, commands:[{command, description}]}` → specs. Lenient on the
    /// slash and the case, strict on validity: a name the server would refuse
    /// to store is not shown as if it existed.
    static func parse(_ data: Data) throws -> [BotCommandSpec] {
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw BotCommandsServiceError.malformed
        }
        if let ok = json["success"] as? Bool, !ok {
            throw BotCommandsServiceError.rejected(json["error"] as? String ?? "")
        }
        let raw = json["commands"] as? [[String: Any]] ?? []
        return raw.compactMap { item in
            let name = BotCommandSpec.normalizeName(item["command"] as? String ?? "")
            guard BotCommandSpec.problem(forName: name) == nil else { return nil }
            return BotCommandSpec(name: name,
                                  description: String((item["description"] as? String ?? "").prefix(BotCommandSpec.maxDescriptionLength)))
        }
    }

    func setCommands(_ commands: [BotCommandSpec], token: String) async throws {
        guard let url = URL(string: "\(baseURL)/api/bot/setMyCommands") else { throw BotCommandsServiceError.malformed }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: Self.body(commands, token: token))
        request.timeoutInterval = 15
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let err = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw err.map { BotCommandsServiceError.rejected($0) } ?? BotCommandsServiceError.http(status)
        }
        _ = try Self.parse(data)
    }

    func commands(token: String) async throws -> [BotCommandSpec] {
        var comps = URLComponents(string: "\(baseURL)/api/bot/getMyCommands")
        comps?.queryItems = [URLQueryItem(name: "token", value: token)]
        guard let url = comps?.url else { throw BotCommandsServiceError.malformed }
        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw BotCommandsServiceError.http(status) }
        return try Self.parse(data)
    }
}

// MARK: - Editor view-model

/// Everything the command editor does, minus the pixels. Writes go through
/// `BotCommandList.upsert`, persistence through `onSave` (BotManager in the
/// app, a closure in the tests), and the server copy — only for a bot that
/// runs REMOTELY, a local bot's commands never leave the device — through a
/// `BotCommandsService`.
@MainActor
final class BotCommandsEditorModel: ObservableObject {
    enum SyncState: Equatable {
        /// Local bot: nothing to sync, by design.
        case localOnly
        case idle
        case syncing
        case synced
        case failed
    }

    @Published private(set) var bot: OSHIBot
    @Published private(set) var sync: SyncState

    private let service: BotCommandsService
    private let onSave: (OSHIBot) -> Void
    private let isRemote: Bool

    init(bot: OSHIBot,
         service: BotCommandsService = HTTPBotCommandsService(),
         isRemote: Bool? = nil,
         onSave: @escaping (OSHIBot) -> Void) {
        self.bot = bot
        self.service = service
        self.onSave = onSave
        let remote = isRemote ?? BotManager.needsServerRegistration(bot)
        self.isRemote = remote
        self.sync = remote ? .idle : .localOnly
    }

    /// The command triggers, in order, for the list.
    var commandTriggers: [BotTrigger] { bot.triggers.filter { $0.type == .command } }
    var commands: [BotCommandSpec] { BotCommandList.commands(of: bot) }
    var starters: [String] { BotCommandList.starters(existing: commands) }

    /// `nil` on success; the problem to show under the form otherwise.
    @discardableResult
    func save(editing triggerID: UUID?, name: String, description: String, reply: String) -> BotCommandSpec.Problem? {
        switch BotCommandList.upsert(bot.triggers, editing: triggerID, name: name,
                                     description: description, reply: reply) {
        case .failure(let p):
            return p
        case .success(let triggers):
            bot.triggers = triggers
            commit()
            return nil
        }
    }

    func remove(triggerID: UUID) {
        bot.triggers.removeAll { $0.id == triggerID }
        commit()
    }

    func remove(atOffsets offsets: IndexSet) {
        let ids = offsets.compactMap { commandTriggers.indices.contains($0) ? commandTriggers[$0].id : nil }
        bot.triggers.removeAll { ids.contains($0.id) }
        commit()
    }

    /// Reorder among command triggers only; other triggers keep their slots.
    func move(fromOffsets source: IndexSet, toOffset destination: Int) {
        var cmds = commandTriggers
        cmds.move(fromOffsets: source, toOffset: destination)
        var it = cmds.makeIterator()
        bot.triggers = bot.triggers.map { $0.type == .command ? (it.next() ?? $0) : $0 }
        commit()
    }

    func addStarter(_ name: String) {
        let key: String
        switch name {
        case "start": key = "bot.commands.starter.start"
        case "help": key = "bot.commands.starter.help"
        default: key = ""
        }
        let description = key.isEmpty ? "" : key.localized
        save(editing: nil, name: name, description: description,
             reply: bot.description.isEmpty ? description : bot.description)
    }

    private func commit() {
        onSave(bot)
        pushToServer()
    }

    /// Re-sends the WHOLE list (`setMyCommands` replaces). Also used to repair
    /// the server copy after `/api/bot/register` wiped it — see BotManager.
    func pushToServer() {
        guard isRemote else { return }
        let token = bot.token
        let list = commands
        sync = .syncing
        Task { [service] in
            do {
                try await service.setCommands(list, token: token)
                self.sync = .synced
            } catch {
                OshiLog.ai.info("⚠️ setMyCommands failed: \(String(describing: error))")
                self.sync = .failed
            }
        }
    }
}
