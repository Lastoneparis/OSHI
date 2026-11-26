//
//  BotManager.swift
//  OSHI - Bot Platform (Telegram BotFather-like)
//
//  Create, manage, and execute bots for automated message streams.
//  Bots can be added to groups/channels with Python-scripted automation.
//  Supports local triggers (keyword, schedule) and remote webhooks.
//

import Foundation
import CryptoKit

// MARK: - Bot Models

struct OSHIBot: Identifiable, Codable, Hashable {
    let id: UUID
    /// `var` since __BOTS_PHASE1_2026_11_24__: "Revoke token" replaces it.
    var token: String          // Unique bot token (like Telegram bot token)
    var name: String
    var username: String       // @username for bot
    var description: String
    var avatarEmoji: String    // Emoji avatar (lightweight)
    var type: BotType
    var isEnabled: Bool
    var permissions: BotPermissions
    var triggers: [BotTrigger]
    var webhookURL: String?    // Remote webhook endpoint
    var scriptContent: String? // Python script content
    var assignedGroups: [UUID] // Groups this bot is in
    var createdAt: Date
    var lastActivity: Date?
    var messagesSent: Int
    var ownerPublicKey: String // Creator's public key
    // __BOTS_PHASE1_2026_11_24__ public profile + per-group rights. Optional so
    // bots saved by older builds decode unchanged.
    /// One line under the name on the profile sheet (≤ 120).
    var about: String? = nil
    /// Found by search when true.
    var isPublic: Bool? = nil
    /// The `@username` the SERVER accepted (setMyProfile succeeded). `username`
    /// is what the owner typed; links are only shared for this one.
    var publishedUsername: String? = nil
    /// What each group/channel granted the bot, by group UUID string.
    var groupPermissions: [String: BotGroupPermissions]? = nil
    /// The server's botId, kept once learned (it survives a token revoke).
    var publicBotId: String? = nil

    static func == (lhs: OSHIBot, rhs: OSHIBot) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

enum BotType: String, Codable, CaseIterable {
    case automation = "automation"    // Responds to triggers/keywords
    case webhook = "webhook"         // HTTP webhook-driven
    case scheduled = "scheduled"     // Sends messages on schedule
    case moderator = "moderator"     // Group moderation (welcome, rules, etc.)

    var icon: String {
        switch self {
        case .automation: return "gearshape.2.fill"
        case .webhook: return "network"
        case .scheduled: return "clock.fill"
        case .moderator: return "shield.checkered"
        }
    }

    var localizedKey: String { "bot.type.\(rawValue)" }
}

struct BotPermissions: Codable, Hashable {
    var canSendMessages: Bool = true
    var canReadMessages: Bool = true
    var canSendMedia: Bool = false
    var canPinMessages: Bool = false
    var canManageMembers: Bool = false
}

struct BotTrigger: Identifiable, Codable, Hashable {
    let id: UUID
    var type: TriggerType
    var condition: String      // Keyword, cron expression, event name
    var responseTemplate: String // Template for bot response
    var isActive: Bool
    /// __BOT_COMMANDS_2026_11_24__ For a `.command` trigger: the short line the
    /// `/` menu shows under `/name` (≤ 256, like Telegram's `BotCommand`).
    /// Optional so bots saved by older builds decode unchanged and a trigger
    /// without one encodes byte-identical to before (see BotCommands.swift).
    var commandDescription: String? = nil

    enum TriggerType: String, Codable, CaseIterable {
        case keyword = "keyword"      // Message contains keyword
        case command = "command"       // /command style
        case schedule = "schedule"     // Cron-like schedule
        case memberJoin = "memberJoin" // New member joined
        case memberLeave = "memberLeave"
        case regex = "regex"          // Regex pattern match

        var icon: String {
            switch self {
            case .keyword: return "text.magnifyingglass"
            case .command: return "terminal.fill"
            case .schedule: return "calendar.badge.clock"
            case .memberJoin: return "person.badge.plus"
            case .memberLeave: return "person.badge.minus"
            case .regex: return "chevron.left.forwardslash.chevron.right"
            }
        }

        var localizedKey: String { "bot.trigger.\(rawValue)" }
    }
}

// MARK: - Manifest  (__BOT_MANIFEST_2026_08_16__, étape 1)

/// WHERE a bot runs. This is the axis `BotType` never had, and it decides two
/// things at once: whether the bot works with no internet, and whether anything
/// leaves the end-to-end envelope. `BotType` answers "what does it do"
/// (moderation, schedule, replies) EXCEPT for `.webhook`, which answers "where
/// does it run" — one enum carrying two unrelated questions, which is why the
/// rule that matters could never be written down.
enum BotRuntime: String, Codable, CaseIterable {
    /// Triggers evaluated on this device against already-decrypted text; the
    /// reply is re-injected into the normal send path, so it is re-encrypted like
    /// any message. Works over mesh, over LoRa, in a plane.
    case local
    /// A webhook or a server. By construction an excerpt of the conversation
    /// leaves E2E — that is the price, not a bug, and it has to be declared.
    case remote
}

/// What the bot is allowed to SEE. `matched` is the default and the important
/// one: the bot receives only the text that fired its trigger, not the thread.
/// The old `canReadMessages: Bool` had exactly one meaning — everything.
///
/// Telegram reached the same default from the other direction: a bot there sees
/// only commands, mentions, replies to itself and service messages unless its
/// owner disables privacy mode.
enum BotReadScope: String, Codable, CaseIterable { case none, matched, all }

/// What the bot is allowed to SAY.
enum BotWriteScope: String, Codable, CaseIterable { case none, reply, post }

/// What a remote bot does when there is no internet. Without this field the
/// off-grid promise is decided by accident (today: the webhook fails silently).
enum BotOfflinePolicy: String, Codable, CaseIterable {
    case queue           // hold it, fire when a link returns
    case skip            // let the turn pass
    case degradeToLocal  // answer from the local template instead
}

struct BotScopes: Codable, Hashable {
    var read: BotReadScope = .matched
    var write: BotWriteScope = .reply
    var media: Bool = false
    var pin: Bool = false
    var members: Bool = false
}

/// Everything a `remote` bot needs, and nothing a `local` one may carry.
struct BotRemoteSpec: Codable, Hashable {
    var endpoint: String
    /// EXHAUSTIVE list of what leaves the device. What the install sheet prints
    /// word for word, and the only thing the runtime is allowed to serialise.
    var forwards: [String]
    var whenOffline: BotOfflinePolicy
}

struct BotLimits: Codable, Hashable {
    /// Not an anti-abuse knob: on LoRa a bot reply competes with human messages
    /// for a tiny link budget.
    var repliesPerMinute: Int = 6
    var maxReplyChars: Int = 2000
}

/// What a bot declares before anyone installs it: human-readable in the install
/// sheet, and — the whole point — read by the runtime at execution time.
struct BotManifest: Codable, Hashable {
    var manifest: Int = 1
    var id: UUID
    var name: String
    var handle: String
    var runtime: BotRuntime
    var scopes: BotScopes
    var triggers: [BotTrigger]
    var limits: BotLimits
    /// Present if and only if `runtime == .remote`.
    var remote: BotRemoteSpec?

    /// DERIVED, never declared — so a bot author cannot promise "sealed" and
    /// then ship a webhook. It is a function of where the code runs, which is
    /// the only thing that actually determines it.
    var confidentiality: String { runtime == .local ? "sealed" : "forwarded" }

    enum CodingKeys: String, CodingKey {
        case manifest, id, name, handle, runtime, scopes, triggers, limits, remote
    }
}

// MARK: - Annuaire  (__BOT_DIRECTORY_2026_08_16__, étape 5)

/// One bot as the directory shows it: the manifest, plus the two facts a reader
/// decides on before installing anything.
///
/// Deliberately carries NO prose. Every string a user reads is built by the view
/// from these facts against keys that already exist in all 34 `.lproj` files —
/// inventing new ones here would mean 34 edits, and a key missing from one of
/// them ships the raw key to that language.
///
/// The listing is the same object as the install sheet's disclosure. One source,
/// so what the card promises and what the runtime enforces cannot drift — which
/// is the failure this whole plan keeps circling: a value declared in one place
/// and read in another eventually stops being read at all.
struct BotDirectoryEntry: Identifiable, Hashable {
    var manifest: BotManifest
    var id: UUID { manifest.id }

    /// THE column no competitor can print. A Telegram bot is an HTTP endpoint on
    /// someone's server by construction; there is no such thing as one that
    /// answers with no internet. Here it is simply where the code runs.
    var worksOffline: Bool { manifest.runtime == .local }

    /// Sealed = the conversation never leaves the envelope. Derived, never
    /// declared — see `BotManifest.confidentiality`.
    var isSealed: Bool { manifest.confidentiality == "sealed" }

    /// Exactly what leaves the device, in the manifest's own words. Empty for a
    /// sealed bot. For a remote bot that declares nothing, empty means UNDECLARED
    /// — which `hasUndeclaredForwarding` turns into a warning rather than a
    /// reassuring silence.
    var forwards: [String] { manifest.remote?.forwards ?? [] }

    /// A remote bot that has not said what it sends. The dangerous state: it
    /// reads as "nothing leaves" on a card that does not distinguish empty from
    /// unanswered.
    var hasUndeclaredForwarding: Bool {
        manifest.runtime == .remote && forwards.isEmpty
    }

    /// What it does when the link is gone. `nil` for a local bot: the question
    /// does not apply, and answering it anyway would suggest it might not run.
    var offlinePolicy: BotOfflinePolicy? { manifest.remote?.whenOffline }
}

enum BotDirectory {

    /// Build the listing from the bots this device holds. Pure: the same input
    /// always yields the same cards, and no card can say anything the manifest
    /// does not.
    nonisolated static func entries(for bots: [OSHIBot]) -> [BotDirectoryEntry] {
        bots.map { BotDirectoryEntry(manifest: BotManager.manifest(for: $0)) }
    }

    /// The filter that is the point of the directory.
    nonisolated static func filter(_ entries: [BotDirectoryEntry],
                                   offlineOnly: Bool = false,
                                   sealedOnly: Bool = false) -> [BotDirectoryEntry] {
        entries.filter { entry in
            if offlineOnly, !entry.worksOffline { return false }
            if sealedOnly, !entry.isSealed { return false }
            return true
        }
    }
}

/// __BOT_SCOPES_ENFORCED_2026_08_16__ (étape 3) What the single exit decided.
/// `content` may come back truncated; `reason` is the machine-readable tag that
/// gets logged and asserted.
struct BotEmitDecision: Equatable {
    var allow: Bool
    var content: String
    var reason: String
}

struct BotMessage: Identifiable, Codable {
    let id: UUID
    let botId: UUID
    let groupId: UUID?
    let content: String
    let timestamp: Date
    let isFromBot: Bool       // true = bot sent, false = user trigger
    var mediaType: String?
    var mediaData: Data?
}

// MARK: - Bot Templates

struct BotTemplate: Identifiable {
    let id: UUID
    let name: String
    let description: String
    let icon: String
    let type: BotType
    let triggers: [BotTrigger]
    let scriptTemplate: String?
}

// MARK: - Bot Manager

@MainActor
final class BotManager: ObservableObject {
    static let shared = BotManager()

    @Published var bots: [OSHIBot] = []
    @Published var botMessages: [UUID: [BotMessage]] = [:] // keyed by botId

    // MARK: __BOT_INLINE_KEYBOARD_2026_09_16__ — bot platform surfaces
    //
    // A bot message can now arrive carrying an inline keyboard. The keyboard is
    // NOT a field on `GroupMessage`: that struct is the wire format for group
    // messages and is decoded by the Android client too, so adding a key to it
    // would change a format three platforms agree on for a payload that only
    // ever comes from the server, one device at a time. It lives here instead,
    // keyed by the LOCAL `GroupMessage.id`, and persists so the buttons survive
    // a relaunch exactly as the message above them does.
    @Published private(set) var replyMarkups: [String: BotReplyMarkupRecord] = [:]
    /// Buttons whose callback is currently in flight, by `slotKey`. Drives the
    /// spinner + disabled state, and stops a double tap from dispatching twice.
    @Published private(set) var callbacksInFlight: Set<String> = []
    /// `bot_callback_answer` with `showAlert == false` → transient banner.
    @Published var botToast: BotToastPayload?
    /// `bot_callback_answer` with `showAlert == true` → modal the user dismisses.
    @Published var botAlert: BotToastPayload?
    /// A `url` or `web_app` button waiting to be shown. Held HERE rather than in
    /// the bubble that was tapped because bubbles live in a lazy stack: scroll the
    /// message off screen and SwiftUI is free to tear its `.sheet` down with it.
    @Published var botPresentation: BotWebPresentation?

    /// Insertion order for `replyMarkups`, so the cache evicts the oldest.
    private var replyMarkupOrder: [String] = []
    private let replyMarkupKey = "oshi_bot_reply_markup_v1"
    private let replyMarkupCap = 400

    private let storageKey = "oshi_bots_v1"
    private let messagesKey = "oshi_bot_messages_v1"
    private var scheduledTimers: [UUID: Timer] = [:]

    private init() {
        loadBots()
        loadReplyMarkups()
        startScheduledBots()
        // Re-register all bots on launch to keep server member lists in sync
        syncAllBotsWithServer()
    }

    /// Re-register all bots with server (keeps group member lists current)
    private func syncAllBotsWithServer() {
        Task {
            // Small delay to let MessageGroupManager load groups first
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            // __BOT_LOCAL_BOTS_LEAVE_NO_TRACE_2026_08_16__ (étape 4) Runs for
            // EVERY bot, local included: `registerBotWithServer` withdraws the
            // local ones. That is the migration — an install that already leaked
            // a roster stops leaking it on the next launch, with no user action.
            for bot in bots where !bot.assignedGroups.isEmpty {
                registerBotWithServer(bot)
            }
            if !bots.isEmpty {
                OshiLog.ai.info("🤖 Auto-synced \(bots.filter { !$0.assignedGroups.isEmpty }.count) bot(s) with server")
            }
        }
    }

    // MARK: - Manifest derivation  (__BOT_MANIFEST_2026_08_16__, étape 1)

    /// Read an existing bot as a manifest. Pure and `nonisolated`, so the rules
    /// are asserted directly in `BotManifestTests` instead of being inferred.
    ///
    /// Nothing is migrated and nothing is written: every bot already on a device
    /// gets a manifest DERIVED from what it already declares, so this step ships
    /// with zero behaviour change and the later steps have something to enforce.
    ///
    /// The derivation:
    ///   * `.webhook` → `remote`, everything else → `local`. That is the split
    ///     `BotType` was hiding.
    ///   * `read: .matched` for everyone. A tightening, and a safe one: no bot
    ///     reads the thread today — `processMessage` only ever sees the single
    ///     message that fired it.
    ///   * the three permissions that were rendered and enforced NOWHERE
    ///     (`canSendMedia`, `canPinMessages`, `canManageMembers`) become real
    ///     scopes here, carrying over whatever the user had toggled.
    ///   * a `remote` bot with no endpoint is still `remote` — its `remote` spec
    ///     is nil and the runtime will refuse to run it, which is the honest
    ///     reading of "a webhook bot whose webhook was never set".
    nonisolated static func manifest(for bot: OSHIBot) -> BotManifest {
        let runtime: BotRuntime = (bot.type == .webhook) ? .remote : .local
        let scopes = BotScopes(
            read: bot.permissions.canReadMessages ? .matched : .none,
            write: bot.permissions.canSendMessages ? .reply : .none,
            media: bot.permissions.canSendMedia,
            pin: bot.permissions.canPinMessages,
            members: bot.permissions.canManageMembers
        )
        var remote: BotRemoteSpec?
        if runtime == .remote, let url = bot.webhookURL, !url.isEmpty {
            remote = BotRemoteSpec(
                endpoint: url,
                // What `sendWebhookEvent` actually posts today. Naming it is the
                // point: it was message text, group id and the SENDER'S PUBLIC
                // KEY, to an arbitrary third-party URL, with no disclosure.
                forwards: ["matched_text", "group_id", "sender_public_key"],
                whenOffline: .queue
            )
        }
        return BotManifest(
            id: bot.id,
            name: bot.name,
            handle: bot.username,
            runtime: runtime,
            scopes: scopes,
            triggers: bot.triggers,
            limits: BotLimits(),
            remote: remote
        )
    }

    /// Manifests for every bot on this device, in creation order.
    var manifests: [BotManifest] { bots.map { BotManager.manifest(for: $0) } }

    // MARK: - Bot Creation (BotFather-like)

    func createBot(name: String, username: String, description: String, type: BotType, ownerPublicKey: String) -> OSHIBot {
        let token = generateBotToken()
        let bot = OSHIBot(
            id: UUID(),
            token: token,
            name: name,
            username: username.lowercased().replacingOccurrences(of: " ", with: "_"),
            description: description,
            avatarEmoji: randomBotEmoji(),
            type: type,
            isEnabled: true,
            permissions: BotPermissions(),
            triggers: [],
            webhookURL: nil,
            scriptContent: defaultScript(for: type),
            assignedGroups: [],
            createdAt: Date(),
            lastActivity: nil,
            messagesSent: 0,
            ownerPublicKey: ownerPublicKey
        )
        bots.append(bot)
        saveBots()
        return bot
    }

    func deleteBot(_ bot: OSHIBot) {
        scheduledTimers[bot.id]?.invalidate()
        scheduledTimers.removeValue(forKey: bot.id)
        bots.removeAll { $0.id == bot.id }
        botMessages.removeValue(forKey: bot.id)
        saveBots()
        saveMessages()
        unregisterBotFromServer(bot)
    }

    /// Unregister bot from OSHI server (revokes external API access).
    /// Internal rather than private since __BOT_LOCAL_BOTS_LEAVE_NO_TRACE_2026_08_16__:
    /// `registerBotWithServer` calls it to withdraw a local bot that an earlier
    /// build had registered.
    func unregisterBotFromServer(_ bot: OSHIBot) {
        Task {
            guard let url = URL(string: "https://oshi-messenger.com/api/bot/unregister?token=\(bot.token)") else { return }
            var request = URLRequest(url: url)
            request.httpMethod = "DELETE"
            request.timeoutInterval = 10
            do {
                let (_, response) = try await URLSession.shared.data(for: request)
                if let httpResponse = response as? HTTPURLResponse {
                    OshiLog.ai.info("🤖 Bot server unregister: \(httpResponse.statusCode)")
                }
            } catch {
                OshiLog.ai.info("⚠️ Bot server unregister failed: \(error.localizedDescription)")
            }
        }
    }

    func updateBot(_ bot: OSHIBot) {
        if let idx = bots.firstIndex(where: { $0.id == bot.id }) {
            bots[idx] = bot
            saveBots()
            // Restart scheduled timers if needed
            if bot.type == .scheduled {
                restartScheduledBot(bot)
            }
        }
    }

    func toggleBot(_ botId: UUID) {
        if let idx = bots.firstIndex(where: { $0.id == botId }) {
            bots[idx].isEnabled.toggle()
            saveBots()
            if bots[idx].isEnabled {
                startScheduledBot(bots[idx])
            } else {
                scheduledTimers[botId]?.invalidate()
                scheduledTimers.removeValue(forKey: botId)
            }
        }
    }

    // MARK: - Bot Assignment to Groups

    func assignBot(_ botId: UUID, toGroup groupId: UUID) {
        if let idx = bots.firstIndex(where: { $0.id == botId }) {
            if !bots[idx].assignedGroups.contains(groupId) {
                bots[idx].assignedGroups.append(groupId)
                saveBots()
            }
        }
    }

    func removeBot(_ botId: UUID, fromGroup groupId: UUID) {
        if let idx = bots.firstIndex(where: { $0.id == botId }) {
            bots[idx].assignedGroups.removeAll { $0 == groupId }
            saveBots()
        }
    }

    func botsForGroup(_ groupId: UUID) -> [OSHIBot] {
        bots.filter { $0.assignedGroups.contains(groupId) && $0.isEnabled }
    }

    // MARK: - Scope enforcement  (__BOT_SCOPES_ENFORCED_2026_08_16__, étape 3)

    /// What the funnel is allowed to let through, decided from the MANIFEST.
    ///
    /// Pure, and the clock is a parameter rather than a call to `Date()`: the
    /// rate limit is the one rule here that a test cannot assert without owning
    /// time, and a limiter you cannot test is a limiter you find out about in
    /// production.
    ///
    /// `content` comes back possibly TRUNCATED rather than refused. A reply that
    /// runs long is a badly written template, not an attack; dropping it would
    /// look to the user exactly like the bot being broken, which is the failure
    /// this whole migration exists to stop.
    nonisolated static func authorizeEmit(manifest: BotManifest,
                                          content: String,
                                          hasMedia: Bool,
                                          recentReplies: [Date],
                                          now: Date) -> BotEmitDecision {
        guard manifest.scopes.write != .none else {
            return BotEmitDecision(allow: false, content: content, reason: "write-scope-none")
        }
        // The first of the three permissions that were rendered in the UI and read
        // by nothing to become real. There is no bot path that sends media today —
        // this is the gate that path must pass through when it is added, and it
        // exists now so the answer is decided once rather than at the call site.
        if hasMedia, !manifest.scopes.media {
            return BotEmitDecision(allow: false, content: content, reason: "media-scope-denied")
        }
        // Sliding window. Not an anti-abuse knob: on LoRa a bot's reply competes
        // with human messages for a link measured in bytes per second, so this is
        // bandwidth sharing.
        let window = now.addingTimeInterval(-60)
        let inWindow = recentReplies.filter { $0 > window }.count
        if inWindow >= max(0, manifest.limits.repliesPerMinute) {
            return BotEmitDecision(allow: false, content: content, reason: "rate-limited")
        }
        let cap = max(1, manifest.limits.maxReplyChars)
        if content.count > cap {
            return BotEmitDecision(allow: true, content: String(content.prefix(cap)), reason: "truncated")
        }
        return BotEmitDecision(allow: true, content: content, reason: "ok")
    }

    /// Reply timestamps per bot, for the sliding window above. In memory on
    /// purpose: a limit whose job is to protect a live radio link has nothing to
    /// say about what happened before the app launched.
    private var recentReplyTimes: [UUID: [Date]] = [:]

    /// May this bot pin a message? May it manage members? No bot-reachable path
    /// does either today, so there is nothing to gate yet — these exist so the
    /// path that adds one asks the manifest instead of inventing an answer, the
    /// way `canPinMessages` and `canManageMembers` were invented and then read by
    /// nobody for months.
    func mayPin(botId: UUID) -> Bool {
        guard let bot = bots.first(where: { $0.id == botId }) else { return false }
        return BotManager.manifest(for: bot).scopes.pin
    }
    func mayManageMembers(botId: UUID) -> Bool {
        guard let bot = bots.first(where: { $0.id == botId }) else { return false }
        return BotManager.manifest(for: bot).scopes.members
    }

    // MARK: - The one exit  (__BOT_RUNTIME_OWNS_DELIVERY_2026_08_16__, étape 2)

    /// The ONLY way a bot speaks. Every reply — trigger, schedule, webhook,
    /// manual post — leaves through here.
    ///
    /// It used to be four separate `NotificationCenter.post("BotResponse")`
    /// calls, caught by an `.onReceive` on the GROUP CHAT VIEW. So a bot could
    /// only answer while its conversation was on screen: close the chat and the
    /// engine still ran, computed a reply, posted a notification, and nobody was
    /// listening. That is not a bot platform, it is a macro that replies to you
    /// while you watch.
    ///
    /// Delivery now belongs to the manager layer, which is alive as long as the
    /// app is. Concentrating it here is also what makes step 3 possible: the
    /// manifest's write scope and rate limit have exactly one place to be read.
    @discardableResult
    func emit(botId: UUID, groupId: UUID, content: String, hasMedia: Bool = false) -> Bool {
        guard let idx = bots.firstIndex(where: { $0.id == botId }) else { return false }
        let bot = bots[idx]
        guard bot.isEnabled else { return false }

        // __BOT_SCOPES_ENFORCED_2026_08_16__ (étape 3) The manifest is read HERE
        // and nowhere else. That is the whole point of having spent step 2
        // collapsing four exits into one: a rule with a single reader cannot
        // drift, and cannot be forgotten at a call site the way `canSendMedia`,
        // `canPinMessages` and `canManageMembers` were forgotten at every one.
        let now = Date()
        let decision = BotManager.authorizeEmit(
            manifest: BotManager.manifest(for: bot),
            content: content,
            hasMedia: hasMedia,
            recentReplies: recentReplyTimes[botId] ?? [],
            now: now
        )
        guard decision.allow else {
            OshiLog.ai.info("🤖 emit refusé (\(decision.reason)) — bot \(bot.name), groupe \(groupId.uuidString.prefix(8))")
            // __BOT_WHY_NOTHING_HAPPENED_2026_08_16__ os_log seul ne sort pas de
            // l'appareil; un refus de scope doit être lisible dans l'export.
            CallFileLogger.shared.log("DIAG_BOT_REFUSED | bot=\(bot.name) | why=\(decision.reason)")
            return false
        }
        if decision.reason != "ok" {
            OshiLog.ai.info("🤖 emit \(decision.reason) — bot \(bot.name)")
        }
        let content = decision.content

        // Keep only the window we still compare against; this array must not grow
        // for the lifetime of the process.
        var times = (recentReplyTimes[botId] ?? []).filter { $0 > now.addingTimeInterval(-60) }
        times.append(now)
        recentReplyTimes[botId] = times

        recordBotMessage(botId: botId, groupId: groupId, content: content, isFromBot: true)

        // __BOT_IN_THE_PIPELINE_2026_11_09__ `bot:<id8>` is now the LOCAL label
        // only (the owner's dark bot bubble; `GroupViews` keys off `hasPrefix("bot:")`).
        // It used to be the wire sender too, and every receiver dropped the reply
        // as `sender-mismatch` (`GroupWireV2.check`, Android `v2ContentAuthentic`):
        // the envelope is sealed by THIS device's identity, so a JSON `bot:` sender
        // could never authenticate. The reply now leaves under the owner's key,
        // body `[Name] text`, flagged `botAuthor` — see `sendBotMessage`.
        let botSenderKey = BotManager.localBotSenderKey(for: bot.id)
        guard MessageGroupManager.shared.sendBotMessage(
            to: groupId,
            botSenderKey: botSenderKey,
            botName: bot.name,
            body: BotManager.botReplyBody(botName: bot.name, content: content)
        ) else {
            CallFileLogger.shared.log("DIAG_BOT_REFUSED | bot=\(bot.name) | why=no-group-or-identity")
            return false
        }

        bots[idx].messagesSent += 1
        bots[idx].lastActivity = Date()
        saveBots()
        return true
    }

    /// __BOT_IN_THE_PIPELINE_2026_11_09__ The owner-device label of a local
    /// bot's rows: `bot:` + first 8 chars of its UUID (what `ownedBot(forSenderKey:)`
    /// resolves). Never sent as a wire sender.
    nonisolated static func localBotSenderKey(for botId: UUID) -> String {
        "bot:" + botId.uuidString.prefix(8)
    }

    /// The visible body every member reads: `[Name] text` (Android `BotManager.emit`).
    nonisolated static func botReplyBody(botName: String, content: String) -> String {
        "[\(botName)] \(content)"
    }

    // MARK: - Post as Bot

    func postAsBot(_ botId: UUID, toGroup groupId: UUID, content: String) {
        emit(botId: botId, groupId: groupId, content: content)
    }

    // MARK: - « Pourquoi rien ne s'est passé »  (__BOT_NO_BOT_HINT_2026_08_17__)

    /// Does this text read as an attempt to run a command?
    ///
    /// Pure, and deliberately narrow: a leading `/` followed by a letter. Not a
    /// lone slash, not a path someone pasted with a space in it, not a control
    /// payload (those start with an emoji sentinel). Getting this wrong in the
    /// permissive direction means nagging someone about bots because they sent a
    /// URL fragment.
    nonisolated static func looksLikeACommand(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("/"), trimmed.count > 1 else { return false }
        let second = trimmed[trimmed.index(trimmed.startIndex, offsetBy: 1)]
        return second.isLetter
    }

    /// Is there a bot in this group that could actually answer?
    func hasUsableBot(inGroup groupId: UUID) -> Bool {
        bots.contains { $0.assignedGroups.contains(groupId) && $0.isEnabled && $0.permissions.canReadMessages }
    }

    private static let noBotHintKey = "oshi_bot_hint_shown_groups_v1"

    /// Should we tell the user, once, that this group has no bot?
    ///
    /// MEASURED, not imagined: two devices, four groups, `totalBots=0` on every
    /// line. The app ships a `/help` TEMPLATE, so `/help` looks like something the
    /// app should answer — but a template is not an instantiated bot, and nothing
    /// said so. The user typed it, nothing happened, and neither of us could tell
    /// "there is no bot" from "the bot is broken" until the diagnostics went in.
    ///
    /// ONCE PER GROUP, persisted. A hint that reappears is not a hint, it is
    /// nagging, and the second showing teaches nothing the first did not.
    func shouldShowNoBotHint(for text: String, groupId: UUID) -> Bool {
        guard BotManager.looksLikeACommand(text) else { return false }
        guard !hasUsableBot(inGroup: groupId) else { return false }
        let shown = Set(UserDefaults.standard.stringArray(forKey: BotManager.noBotHintKey) ?? [])
        return !shown.contains(groupId.uuidString)
    }

    /// Record that this group has had its one hint.
    func markNoBotHintShown(groupId: UUID) {
        var shown = UserDefaults.standard.stringArray(forKey: BotManager.noBotHintKey) ?? []
        guard !shown.contains(groupId.uuidString) else { return }
        shown.append(groupId.uuidString)
        // Bounded: this is a courtesy flag, not a database.
        if shown.count > 500 { shown.removeFirst(shown.count - 500) }
        UserDefaults.standard.set(shown, forKey: BotManager.noBotHintKey)
    }

    // MARK: - Trigger Processing

    /// __BOT_RUNTIME_OWNS_DELIVERY_2026_08_16__ (étape 2)
    ///
    /// Is this message something a trigger may look at? Pure, so the two rules
    /// that keep a group habitable are asserted rather than assumed.
    ///
    ///   1. A BOT'S OWN OUTPUT NEVER FIRES A TRIGGER. This was harmless while
    ///      bots only ever saw the message you had just typed yourself. Now that
    ///      they see INBOUND traffic, two bots with crossing keywords would
    ///      answer each other forever — and on LoRa that loop competes with human
    ///      messages for a link measured in bytes per second.
    ///   2. Control payloads are not prose. A delivery receipt, a typing ping or
    ///      a reaction could match a keyword trigger and make a bot reply to the
    ///      plumbing.
    ///
    /// `botAuthored` (__BOT_IN_THE_PIPELINE_2026_11_09__) is the wire's
    /// `botAuthor` flag: a local bot's reply reaches other members under its
    /// OWNER's real key, so the `bot:` prefix only catches it on the owner's
    /// device. The flag can only SUPPRESS a trigger, never enable one.
    nonisolated static func shouldEvaluateTriggers(senderKey: String, content: String, botAuthored: Bool = false) -> Bool {
        if botAuthored { return false }
        if senderKey.hasPrefix("bot:") { return false }
        let trimmed = content.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return false }
        if OSHIControlPayload.isControl(trimmed) { return false }
        return true
    }

    /// Evaluate every assigned bot against one message.
    ///
    /// Called from `MessageGroupManager` on BOTH sides now — the message you send
    /// and the message you receive. Until today the only caller was
    /// `GroupViews.sendGroupMessage`, which meant a bot answered exclusively to
    /// its owner's own typing, in a group screen that had to be open. Another
    /// member's `/help` reached no trigger, ever.
    func processMessage(content: String, groupId: UUID, senderKey: String, botAuthored: Bool = false) {
        // __BOT_WHY_NOTHING_HAPPENED_2026_08_16__
        // « J'ai tapé /help et rien ne s'est passé » avait QUATRE causes possibles
        // — aucun bot dans ce groupe, bot désactivé, aucun déclencheur qui
        // correspond, ou une sortie refusée par un scope — et pas une seule ne
        // s'écrivait dans le log exportable. Un utilisateur ne peut pas
        // distinguer « il n'y a pas de bot » de « le bot est cassé », et moi non
        // plus. Chaque branche dit maintenant laquelle c'est.
        guard BotManager.shouldEvaluateTriggers(senderKey: senderKey, content: content, botAuthored: botAuthored) else {
            CallFileLogger.shared.log("DIAG_BOT_SKIP | why=guard | from=\(senderKey.prefix(12)) | botAuthored=\(botAuthored) | len=\(content.count)")
            return
        }

        let assigned = botsForGroup(groupId)
        let usable = assigned.filter { $0.isEnabled && $0.permissions.canReadMessages }
        guard !usable.isEmpty else {
            CallFileLogger.shared.log("DIAG_BOT_NONE | group=\(groupId.uuidString.prefix(8)) | assignedEnabled=\(assigned.count) | readable=0 | totalBots=\(bots.count)")
            return
        }

        var fired = 0
        for bot in usable {
            for trigger in bot.triggers where trigger.isActive {
                guard shouldFireTrigger(trigger, for: content) else { continue }
                fired += 1
                let response = generateResponse(trigger: trigger, bot: bot, input: content)
                let sent = emit(botId: bot.id, groupId: groupId, content: response)
                CallFileLogger.shared.log("DIAG_BOT_FIRE | bot=\(bot.name) | trigger=\(trigger.type.rawValue):\(trigger.condition.prefix(16)) | emitted=\(sent)")
            }
        }
        if fired == 0 {
            let conditions = usable.flatMap { $0.triggers.filter(\.isActive).map { "\($0.type.rawValue):\($0.condition.prefix(12))" } }
            CallFileLogger.shared.log("DIAG_BOT_NOMATCH | group=\(groupId.uuidString.prefix(8)) | bots=\(usable.count) | triggers=[\(conditions.joined(separator: ","))] | input=\(content.prefix(24))")
        }
    }

    private func shouldFireTrigger(_ trigger: BotTrigger, for content: String) -> Bool {
        switch trigger.type {
        case .keyword:
            return content.localizedCaseInsensitiveContains(trigger.condition)
        case .command:
            let cmd = trigger.condition.hasPrefix("/") ? trigger.condition : "/\(trigger.condition)"
            return content.trimmingCharacters(in: .whitespaces).lowercased().hasPrefix(cmd.lowercased())
        case .regex:
            if let regex = try? NSRegularExpression(pattern: trigger.condition, options: .caseInsensitive) {
                let range = NSRange(content.startIndex..., in: content)
                return regex.firstMatch(in: content, range: range) != nil
            }
            return false
        case .memberJoin:
            return content.contains("[MEMBER_JOIN]")
        case .memberLeave:
            return content.contains("[MEMBER_LEAVE]")
        case .schedule:
            return false // Handled by timer
        }
    }

    private func generateResponse(trigger: BotTrigger, bot: OSHIBot, input: String) -> String {
        var response = trigger.responseTemplate
        // Template variable substitution
        response = response.replacingOccurrences(of: "{{input}}", with: input)
        response = response.replacingOccurrences(of: "{{bot_name}}", with: bot.name)
        response = response.replacingOccurrences(of: "{{date}}", with: DateFormatter.localizedString(from: Date(), dateStyle: .medium, timeStyle: .short))
        response = response.replacingOccurrences(of: "{{timestamp}}", with: "\(Int(Date().timeIntervalSince1970))")
        return response
    }

    // MARK: - Scheduled Bots

    private func startScheduledBots() {
        for bot in bots where bot.type == .scheduled && bot.isEnabled {
            startScheduledBot(bot)
        }
    }

    private func startScheduledBot(_ bot: OSHIBot) {
        scheduledTimers[bot.id]?.invalidate()
        for trigger in bot.triggers where trigger.type == .schedule && trigger.isActive {
            // Parse interval from condition (e.g., "3600" for hourly, "60" for every minute)
            if let interval = TimeInterval(trigger.condition), interval > 0 {
                let timer = Timer.scheduledTimer(withTimeInterval: max(interval, 60), repeats: true) { [weak self] _ in
                    Task { @MainActor in
                        guard let self = self else { return }
                        for groupId in bot.assignedGroups {
                            // __BOT_RUNTIME_OWNS_DELIVERY_2026_08_16__ A scheduled
                            // bot firing into a closed screen used to compute its
                            // reply and drop it on the floor.
                            let response = self.generateResponse(trigger: trigger, bot: bot, input: "")
                            self.emit(botId: bot.id, groupId: groupId, content: response)
                        }
                    }
                }
                scheduledTimers[bot.id] = timer
            }
        }
    }

    private func restartScheduledBot(_ bot: OSHIBot) {
        scheduledTimers[bot.id]?.invalidate()
        scheduledTimers.removeValue(forKey: bot.id)
        if bot.isEnabled {
            startScheduledBot(bot)
        }
    }

    // MARK: - Webhook Support

    func sendWebhookEvent(bot: OSHIBot, event: [String: Any]) {
        guard let urlStr = bot.webhookURL, let url = URL(string: urlStr) else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("OSHI-Bot/\(bot.token)", forHTTPHeaderField: "Authorization")
        request.httpBody = try? JSONSerialization.data(withJSONObject: event)
        URLSession.shared.dataTask(with: request) { data, _, _ in
            // Process webhook response if any
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let responseText = json["text"] as? String else { return }
            Task { @MainActor in
                if let groupId = event["group_id"] as? String, let gid = UUID(uuidString: groupId) {
                    // __BOT_RUNTIME_OWNS_DELIVERY_2026_08_16__
                    self.emit(botId: bot.id, groupId: gid, content: responseText)
                }
            }
        }.resume()
    }

    // MARK: - Message History

    func recordBotMessage(botId: UUID, groupId: UUID?, content: String, isFromBot: Bool) {
        let msg = BotMessage(
            id: UUID(),
            botId: botId,
            groupId: groupId,
            content: content,
            timestamp: Date(),
            isFromBot: isFromBot
        )
        if botMessages[botId] == nil {
            botMessages[botId] = []
        }
        botMessages[botId]?.append(msg)
        // Keep last 500 messages per bot
        if let count = botMessages[botId]?.count, count > 500 {
            botMessages[botId] = Array(botMessages[botId]!.suffix(500))
        }
        saveMessages()
    }

    // MARK: - Server Registration (Telegram-like Bot API)

    /// __BOT_LOCAL_BOTS_LEAVE_NO_TRACE_2026_08_16__ (étape 4)
    ///
    /// Does this bot have any business existing on the server at all?
    ///
    /// Registration uploads the GROUP NAME and the FULL LIST OF MEMBER PUBLIC
    /// KEYS, in cleartext, and it is what makes `/api/bot/send` accept the bot's
    /// token — a route that then queues each message per member as base64 JSON,
    /// also in cleartext. So adding one bot to a group put that group's roster
    /// and traffic on the server in the clear. For an app whose whole argument is
    /// end-to-end encryption, that is not a detail.
    ///
    /// A `local` bot buys nothing from any of it: its triggers run on the device
    /// and its replies go out through the normal encrypted group send path. The
    /// registration existed only so an outside process could drive the bot —
    /// which is the definition of a REMOTE bot.
    ///
    /// BEHAVIOUR CHANGE, deliberate and stated: a local bot's token no longer
    /// works against `/api/bot/send`. A bot driven from curl or Python IS remote;
    /// give it a webhook type and it keeps working. Anything else would mean
    /// keeping the roster of every group with a bot in it readable on the server
    /// so that an undeclared external caller can stay undeclared.
    nonisolated static func needsServerRegistration(_ bot: OSHIBot) -> Bool {
        manifest(for: bot).runtime == .remote
    }

    /// Register bot with OSHI server for external API access (Python, curl, etc.)
    func registerBotWithServer(_ bot: OSHIBot) {
        guard BotManager.needsServerRegistration(bot) else {
            // Not merely "skip": a bot that was registered under an earlier build
            // still has its group name and member list sitting there. Withdraw it.
            OshiLog.ai.info("🤖 bot local « \(bot.name) » — aucune inscription serveur; retrait de toute trace existante")
            unregisterBotFromServer(bot)
            return
        }
        Task { await registerBotWithServerNow(bot) }
    }

    /// __BOTS_PHASE1_2026_11_24__ The same registration, awaitable — BotFather
    /// needs the record to exist before it can claim a `@username` on it.
    /// `true` when the server answered 2xx.
    @discardableResult
    func registerBotWithServerNow(_ bot: OSHIBot) async -> Bool {
        guard BotManager.needsServerRegistration(bot) else { return false }
        // 🔴 __BOT_PARTIAL_SYNC_2026_08_22__ Ce `compactMap` a coupé le canal
        // AI pendant plus d'une journée, et voici comment.
        //
        // `/api/bot/update-groups` fait `bot.groups = groups` — un
        // REMPLACEMENT INTÉGRAL, pas une fusion. Ce qu'on envoie devient la
        // vérité côté serveur.
        //
        // Or ce `compactMap` rend `nil`, en silence, pour tout groupe assigné
        // que cet appareil ne trouve pas dans `MessageGroupManager.shared.groups`
        // à l'instant précis de l'appel — juste après le lancement, avant que
        // les groupes soient chargés, ou pendant une reconstruction. Une liste
        // AMPUTÉE part alors, et le serveur oublie les groupes manquants.
        //
        // Constaté le 2026-08-22: le bot du pont Telegram n'avait plus qu'un
        // seul groupe côté serveur, et le canal AI recevait
        // « HTTP 403 Bot is not assigned to this group » toutes les deux
        // minutes — 6 300 fois. Telegram recevait, OSHI non.
        //
        // La règle: une liste dont on SAIT qu'elle est incomplète ne doit
        // jamais devenir la vérité. On préfère ne rien envoyer et réessayer
        // plus tard — l'ancienne liste, elle, est correcte.
        let groups: [[String: Any]] = bot.assignedGroups.compactMap { groupId in
            guard let group = MessageGroupManager.shared.groups.first(where: { $0.id == groupId }) else { return nil }
            return [
                "id": groupId.uuidString,
                "name": group.name,
                "members": group.members.map { $0.publicKey },
                // __BOTS_PHASE1_2026_11_24__ the rights granted at add time.
                "kind": group.groupType == .adminOnly ? "channel" : "group",
                "permissions": (bot.groupPermissions?[groupId.uuidString] ?? BotGroupPermissions()).wire
            ]
        }

        guard groups.count == bot.assignedGroups.count else {
            let missing = bot.assignedGroups.count - groups.count
            OshiLog.ai.info("🤖 ⛔️ Synchro du bot « \(bot.name) » ANNULÉE: \(missing) groupe(s) assigné(s) introuvable(s) localement. Envoyer cette liste effacerait ces groupes côté serveur.")
            return false
        }

        let payload: [String: Any] = [
            "token": bot.token,
            "botName": bot.name,
            "ownerPublicKey": bot.ownerPublicKey,
            "groups": groups
        ]

        guard let url = URL(string: "https://oshi-messenger.com/api/bot/register"),
              let body = try? JSONSerialization.data(withJSONObject: payload) else { return false }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        request.timeoutInterval = 10

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            if let httpResponse = response as? HTTPURLResponse {
                OshiLog.ai.info("🤖 Bot server register: \(httpResponse.statusCode)")
                if let result = String(data: data, encoding: .utf8) {
                    OshiLog.ai.info("   Response: \(result)")
                }
                // __BOT_COMMANDS_2026_11_24__ `/api/bot/register` REPLACES the
                // record (`botRegistry.set(token, record)`), which drops the
                // `commands` that `setMyCommands` stored on it. Every group
                // change re-registers, so without this a bot's `/` menu would
                // empty itself server-side each time it joined a group.
                let commands = BotCommandList.commands(of: bot)
                if (200..<300).contains(httpResponse.statusCode), !commands.isEmpty {
                    do {
                        try await HTTPBotCommandsService().setCommands(commands, token: bot.token)
                    } catch {
                        OshiLog.ai.info("⚠️ setMyCommands after register failed: \(String(describing: error))")
                    }
                }
                return (200..<300).contains(httpResponse.statusCode)
            }
        } catch {
            OshiLog.ai.info("⚠️ Bot server register failed: \(error.localizedDescription)")
        }
        return false
    }

    /// Re-register all bots assigned to a group (call when group membership changes)
    func syncBotsForGroup(_ groupId: UUID) {
        let affected = bots.filter { $0.assignedGroups.contains(groupId) }
        for bot in affected {
            registerBotWithServer(bot)
        }
        if !affected.isEmpty {
            OshiLog.ai.info("🤖 Synced \(affected.count) bot(s) for group \(groupId.uuidString.prefix(8))...")
        }
    }

    /// Notify the server that a member joined a group — adds their key to all bots for that group.
    /// This is lightweight: it doesn't replace the full member list, just appends the new key.
    /// 🔒 Sends the OWNING BOT'S TOKEN with each call (2026-08-04).
    ///
    /// `/api/bot/add-member` used to accept `{groupId, memberPublicKey}` from
    /// anyone and add that key to every bot in the group. Since bot posts are
    /// queued per member as base64 — not encrypted — and `/api/pending/:key` is
    /// fetched with no authentication, knowing a group id was enough to
    /// subscribe yourself to a group's entire bot feed in cleartext.
    ///
    /// The server now requires the token of the bot whose membership is being
    /// edited, so this walks the bots THIS device owns for that group and calls
    /// once per bot. Same end state, and a caller who owns no bot in the group
    /// can no longer change it.
    func notifyServerMemberJoined(groupId: UUID, memberPublicKey: String) {
        // __BOT_LOCAL_BOTS_LEAVE_NO_TRACE_2026_08_16__ (étape 4) Only bots that
        // are legitimately ON the server. Pushing a member key for a local bot
        // would be re-uploading the roster this step exists to remove.
        let owned = bots.filter { $0.assignedGroups.contains(groupId) && BotManager.needsServerRegistration($0) }
        guard !owned.isEmpty else { return }   // nothing of ours to update

        for bot in owned {
            Task {
                guard let url = URL(string: "https://oshi-messenger.com/api/bot/add-member") else { return }
                let payload: [String: Any] = [
                    "token": bot.token,
                    "groupId": groupId.uuidString,
                    "memberPublicKey": memberPublicKey
                ]
                guard let body = try? JSONSerialization.data(withJSONObject: payload) else { return }

                var request = URLRequest(url: url)
                request.httpMethod = "POST"
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                request.httpBody = body
                request.timeoutInterval = 10

                do {
                    let (data, response) = try await URLSession.shared.data(for: request)
                    if let http = response as? HTTPURLResponse, http.statusCode == 200 {
                        if let result = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                           let updated = result["botsUpdated"] as? Int {
                            OshiLog.ai.info("🤖 Server bot add-member: \(updated) bot(s) updated for \(memberPublicKey.prefix(8))...")
                        }
                    } else if let http = response as? HTTPURLResponse {
                        OshiLog.ai.info("⚠️ Bot add-member rejected (HTTP \(http.statusCode)) for bot \(bot.token.prefix(8))...")
                    }
                } catch {
                    OshiLog.ai.info("⚠️ Bot add-member failed: \(error.localizedDescription)")
                }
            }
        }
    }

    // MARK: - Persistence

    private func saveBots() {
        guard let data = try? JSONEncoder().encode(bots) else { return }
        // Security (M1): bot tokens are server-side bearer credentials. Persist them in the
        // Keychain (ThisDeviceOnly, not iCloud-synced) instead of UserDefaults, whose plist is
        // captured in unencrypted local/iCloud backups.
        do {
            try KeychainHelper.save(key: storageKey, data: data)
            // Purge any legacy plaintext copy left behind in UserDefaults.
            UserDefaults.standard.removeObject(forKey: storageKey)
        } catch {
            OshiLog.ai.warning("⚠️ Failed to save bots to Keychain: \(error.localizedDescription)")
        }
    }

    private func loadBots() {
        // Prefer the Keychain; fall back to (and migrate from) the legacy UserDefaults blob.
        if let data = try? KeychainHelper.load(key: storageKey),
           let decoded = try? JSONDecoder().decode([OSHIBot].self, from: data) {
            bots = decoded
        } else if let data = UserDefaults.standard.data(forKey: storageKey),
                  let decoded = try? JSONDecoder().decode([OSHIBot].self, from: data) {
            bots = decoded
            saveBots() // one-time migration into the Keychain (also purges the UserDefaults copy)
        }
        // __BOT_HISTORY_OFF_THE_PLIST_2026_08_18__ Même chemin que les bots
        // ci-dessus: trousseau d'abord, migration unique depuis l'ancien blob.
        if let data = try? KeychainHelper.load(key: messagesKey),
           let decoded = try? JSONDecoder().decode([UUID: [BotMessage]].self, from: data) {
            botMessages = decoded
        } else if let msgData = UserDefaults.standard.data(forKey: messagesKey),
                  let decoded = try? JSONDecoder().decode([UUID: [BotMessage]].self, from: msgData) {
            botMessages = decoded
            saveMessages() // migration unique (purge aussi la copie UserDefaults)
        }
    }

    /// __BOT_HISTORY_OFF_THE_PLIST_2026_08_18__
    ///
    /// Signalé en divulgation privée par Turáni Gergely. Son rapport visait aussi
    /// les JETONS de bot: ceux-là étaient déjà passés au trousseau (voir
    /// `saveBots`), il lisait une révision plus ancienne. L'HISTORIQUE, lui,
    /// partait toujours dans les préférences.
    ///
    /// `BotMessage` porte `content` et `mediaData`. Les préférences d'une app
    /// sont un plist capturé tel quel par les sauvegardes locales et iCloud — le
    /// commentaire de `DoubleRatchet.saveSessions()` dit déjà exactement ça pour
    /// les secrets du ratchet. Pour une messagerie privée, y écrire du contenu de
    /// message en clair contredit ce que l'app promet.
    ///
    /// Même traitement que les bots: trousseau `ThisDeviceOnly`, non synchronisé,
    /// et la copie héritée est purgée APRÈS une écriture réussie — jamais avant,
    /// sinon un trousseau verrouillé effacerait l'historique.
    private func saveMessages() {
        guard let data = try? JSONEncoder().encode(botMessages) else { return }
        do {
            try KeychainHelper.save(key: messagesKey, data: data)
            UserDefaults.standard.removeObject(forKey: messagesKey)
        } catch {
            OshiLog.ai.warning("⚠️ historique de bot non enregistré au trousseau: \(error.localizedDescription)")
        }
    }

    // MARK: - Token Generation

    private func generateBotToken() -> String {
        let random = (0..<32).map { _ in UInt8.random(in: 0...255) }
        let hash = SHA256.hash(data: Data(random))
        return Data(hash).prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    private func randomBotEmoji() -> String {
        let emojis = ["🤖", "🔧", "⚡", "🎯", "📡", "🛡️", "🔔", "📊", "🌐", "🎲", "🧠", "💡"]
        return emojis.randomElement() ?? "🤖"
    }

    private func defaultScript(for type: BotType) -> String {
        switch type {
        case .automation:
            return """
            # OSHI Bot Script
            # Variables: {{input}}, {{bot_name}}, {{date}}, {{timestamp}}
            #
            # This bot responds to keyword triggers.
            # Configure triggers in the bot settings.
            """
        case .webhook:
            return """
            # OSHI Webhook Bot
            # Set your webhook URL in bot settings.
            # POST requests will be sent with JSON:
            # {
            #   "message": "user message",
            #   "group_id": "uuid",
            #   "sender": "public_key",
            #   "bot_token": "your_token"
            # }
            # Respond with: {"text": "bot response"}
            """
        case .scheduled:
            return """
            # OSHI Scheduled Bot
            # Set schedule interval in trigger condition (seconds).
            # Examples: 3600 = hourly, 86400 = daily
            # Response template supports {{date}}, {{timestamp}}
            """
        case .moderator:
            return """
            # OSHI Moderator Bot
            # Automatically welcomes new members and enforces rules.
            # Configure member_join trigger for welcome message.
            """
        }
    }

    // MARK: - Bot Templates

    static let templates: [BotTemplate] = [
        BotTemplate(
            id: UUID(),
            name: "bot.template.welcome.name".localized,
            description: "bot.template.welcome.desc".localized,
            icon: "hand.wave.fill",
            type: .moderator,
            triggers: [
                BotTrigger(
                    id: UUID(),
                    type: .memberJoin,
                    condition: "member_join",
                    responseTemplate: "bot.template.welcome.response".localized,
                    isActive: true
                )
            ],
            scriptTemplate: nil
        ),
        BotTemplate(
            id: UUID(),
            name: "bot.template.faq.name".localized,
            description: "bot.template.faq.desc".localized,
            icon: "questionmark.circle.fill",
            type: .automation,
            triggers: [
                BotTrigger(
                    id: UUID(),
                    type: .command,
                    condition: "/help",
                    responseTemplate: "bot.template.faq.response".localized,
                    isActive: true
                )
            ],
            scriptTemplate: nil
        ),
        BotTemplate(
            id: UUID(),
            name: "bot.template.reminder.name".localized,
            description: "bot.template.reminder.desc".localized,
            icon: "bell.fill",
            type: .scheduled,
            triggers: [
                BotTrigger(
                    id: UUID(),
                    type: .schedule,
                    condition: "86400",
                    responseTemplate: "bot.template.reminder.response".localized,
                    isActive: true
                )
            ],
            scriptTemplate: nil
        ),
        BotTemplate(
            id: UUID(),
            name: "bot.template.webhook.name".localized,
            description: "bot.template.webhook.desc".localized,
            icon: "network",
            type: .webhook,
            triggers: [],
            scriptTemplate: nil
        ),
        BotTemplate(
            id: UUID(),
            name: "bot.template.echo.name".localized,
            description: "bot.template.echo.desc".localized,
            icon: "arrow.turn.up.left",
            type: .automation,
            triggers: [
                BotTrigger(
                    id: UUID(),
                    type: .command,
                    condition: "/echo",
                    responseTemplate: "{{input}}",
                    isActive: true
                )
            ],
            scriptTemplate: nil
        ),
        BotTemplate(
            id: UUID(),
            name: "bot.template.stats.name".localized,
            description: "bot.template.stats.desc".localized,
            icon: "chart.bar.fill",
            type: .automation,
            triggers: [
                BotTrigger(
                    id: UUID(),
                    type: .command,
                    condition: "/stats",
                    responseTemplate: "bot.template.stats.response".localized,
                    isActive: true
                )
            ],
            scriptTemplate: nil
        ),
    ]
}

// MARK: - Inline keyboards  (__BOT_INLINE_KEYBOARD_2026_09_16__)

/// One button of a bot's inline keyboard.
///
/// The three actions are three OPTIONAL fields rather than an enum with
/// associated values, for one reason: this struct is decoded from whatever the
/// server sends and re-encoded into the on-device cache, and a flat shape has no
/// discriminator that a future server version can get wrong. `kind` reads the
/// shape back out, and `BotInlineKeyboard.parse` is the only thing allowed to
/// build one — it refuses anything that does not carry EXACTLY one action.
struct BotInlineButton: Identifiable, Codable, Hashable {
    let id: UUID
    var text: String
    var callbackData: String?
    var urlString: String?
    var webAppURL: String?

    enum Kind: Hashable { case callback, url, webApp }

    var kind: Kind {
        if callbackData != nil { return .callback }
        if webAppURL != nil { return .webApp }
        return .url
    }

    var icon: String {
        switch kind {
        case .callback: return "bolt.fill"
        case .url: return "arrow.up.right.square"
        case .webApp: return "square.grid.2x2.fill"
        }
    }
}

/// Rows of buttons, exactly as Telegram shapes them.
struct BotInlineKeyboard: Codable, Hashable {
    var rows: [[BotInlineButton]]

    var isEmpty: Bool { rows.allSatisfy { $0.isEmpty } }

    /// The server validates this already. We validate it AGAIN, because "the
    /// server already checked" is a statement about the server that is running
    /// today, and the thing that crashes when it stops being true is a phone in
    /// someone's hand. Everything here fails by DROPPING the offending button or
    /// row, never by throwing and never by trapping: a malformed keyboard must
    /// degrade to a message with fewer buttons, or none.
    static let maxRows = 10
    static let maxButtonsPerRow = 8
    /// Telegram's own ceiling for `callback_data` is 64 bytes; a longer one is a
    /// payload we would POST back verbatim, so it is refused rather than clipped.
    static let maxCallbackDataBytes = 64
    static let maxButtonTextChars = 64

    static func parse(_ raw: Any?) -> BotInlineKeyboard? {
        guard let dict = raw as? [String: Any],
              let rawRows = dict["inline_keyboard"] as? [Any] else { return nil }

        var rows: [[BotInlineButton]] = []
        for rawRow in rawRows.prefix(maxRows) {
            guard let rawButtons = rawRow as? [Any] else { continue }
            var row: [BotInlineButton] = []
            for rawButton in rawButtons.prefix(maxButtonsPerRow) {
                guard let button = parseButton(rawButton) else { continue }
                row.append(button)
            }
            if !row.isEmpty { rows.append(row) }
        }
        guard !rows.isEmpty else { return nil }
        return BotInlineKeyboard(rows: rows)
    }

    private static func parseButton(_ raw: Any) -> BotInlineButton? {
        guard let dict = raw as? [String: Any] else { return nil }
        let text = (dict["text"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !text.isEmpty else { return nil }

        let callback = nonEmpty(dict["callback_data"] as? String)
        let url = nonEmpty(dict["url"] as? String)
        let webApp = nonEmpty((dict["web_app"] as? [String: Any])?["url"] as? String)

        // EXACTLY ONE. Two actions on one button is not "pick the first" — it is
        // a message we cannot render the way its author meant, so it is dropped.
        guard [callback, url, webApp].compactMap({ $0 }).count == 1 else { return nil }

        if let callback, callback.utf8.count > maxCallbackDataBytes { return nil }
        if let url, !isOpenableURL(url) { return nil }
        if let webApp, !isOpenableURL(webApp) { return nil }

        return BotInlineButton(
            id: UUID(),
            text: String(text.prefix(maxButtonTextChars)),
            callbackData: callback,
            urlString: url,
            webAppURL: webApp
        )
    }

    private static func nonEmpty(_ s: String?) -> String? {
        guard let s, !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return s
    }

    /// http/https only. `SFSafariViewController` and `WKWebView` handle nothing
    /// else, and a `javascript:` or custom-scheme button handed to `UIApplication`
    /// would let a bot author drive the phone out of the app.
    static func isOpenableURL(_ s: String) -> Bool {
        guard let url = URL(string: s), let scheme = url.scheme?.lowercased() else { return false }
        guard scheme == "https" || scheme == "http" else { return false }
        return url.host?.isEmpty == false
    }
}

/// What the device remembers about one bot message that carried a keyboard.
///
/// `sourceMessageId` is kept as the server SPELLED it. The local `GroupMessage`
/// id is `UUID(uuidString: messageId) ?? UUID()` — a server id that is not a
/// UUID becomes a random one, and posting that back to `/api/bot/dispatch` would
/// name a message the server has never heard of.
struct BotReplyMarkupRecord: Codable, Hashable {
    var keyboard: BotInlineKeyboard
    var sourceMessageId: String
    var groupId: String
    /// What the queue envelope carried in `botToken` — the server truncates this
    /// to 8 characters plus "...", so it identifies a bot but is NOT a credential.
    var botTokenPrefix: String?
    var botName: String?
    /// __BOT_PUBLIC_ID_2026_09_16__ `sha256("oshi-bot-id:" + token)[:16]`, computed
    /// and published by the server on every `bot_message` envelope.
    ///
    /// The field that made buttons work for bots the user does NOT own. It is a
    /// one-way function of the token, so it names a bot without being able to act
    /// as one — which is why it can be handed to every member of a group, and why
    /// `/api/bot/dispatch` can accept it in place of the secret as long as it also
    /// checks that the caller is a member of a group that bot serves.
    ///
    /// Optional because a record persisted before the server shipped this has none,
    /// and because the encrypted `bot_message` path may still arrive without it.
    var botId: String?
}

/// What came back from `/api/bot/dispatch` or `/api/bot/miniapp/session`.
///
/// __BOT_PUBLIC_ID_2026_09_16__ `notMember` and `unknownBot` exist because the
/// server now answers those two cases precisely (403 / 404) and they are the two
/// a user can do something about. A single `failed` for all three would send
/// someone to retry a button that cannot ever work.
enum BotDispatchOutcome: Error {
    case ok
    /// 403 — the caller is not in a group this bot serves. The normal way to see
    /// this is to press a button on an old message after leaving the group.
    case notMember
    /// 404 — the server does not know this `botId` any more (bot deleted).
    case unknownBot
    /// Network, timeout, 5xx, or `success: false`.
    case failed
}

/// A `bot_callback_answer`, on its way to a banner or an alert.
struct BotToastPayload: Identifiable, Equatable {
    let id = UUID()
    var text: String
    var botName: String
    var isAlert: Bool
}

/// A `url` or `web_app` button that has been tapped and is ready to be shown.
struct BotWebPresentation: Identifiable, Equatable {
    let id = UUID()
    var url: URL
    var botName: String
    /// `nil` → a plain web page in `SFSafariViewController`.
    /// non-nil → a mini app in `WKWebView`, with this signed query string handed
    /// to the page both in the fragment and over the JS bridge.
    var initData: String?
}

// MARK: - Bot platform client  (__BOT_INLINE_KEYBOARD_2026_09_16__)

extension BotManager {

    private static let platformBaseURL = "https://oshi-messenger.com"

    // MARK: Reply-markup cache

    /// Identifies one button slot across renders. Row/column rather than the
    /// button's UUID, because the UUID is regenerated whenever the keyboard is
    /// re-parsed from the wire and an in-flight spinner must survive that.
    nonisolated static func slotKey(messageId: String, row: Int, column: Int) -> String {
        "\(messageId)#\(row).\(column)"
    }

    func replyMarkup(forMessageId id: String) -> BotReplyMarkupRecord? {
        replyMarkups[id]
    }

    /// Store the keyboard that came with a bot message. Called from the queue
    /// processing path; idempotent, because the same envelope is re-read from the
    /// queue until the server acknowledges the ack.
    func storeReplyMarkup(_ record: BotReplyMarkupRecord, forMessageId id: String) {
        guard !record.keyboard.isEmpty else { return }
        if replyMarkups[id] == record { return }
        if replyMarkups[id] == nil { replyMarkupOrder.append(id) }
        replyMarkups[id] = record
        if replyMarkupOrder.count > replyMarkupCap {
            let excess = replyMarkupOrder.count - replyMarkupCap
            for stale in replyMarkupOrder.prefix(excess) { replyMarkups.removeValue(forKey: stale) }
            replyMarkupOrder.removeFirst(excess)
        }
        saveReplyMarkups()
    }

    /// __BOTS_PHASE1_2026_11_24__ The bot removed its buttons, or the post.
    func removeReplyMarkup(forMessageId id: String) {
        guard replyMarkups.removeValue(forKey: id) != nil else { return }
        replyMarkupOrder.removeAll { $0 == id }
        saveReplyMarkups()
    }

    func isCallbackInFlight(messageId: String, row: Int, column: Int) -> Bool {
        callbacksInFlight.contains(BotManager.slotKey(messageId: messageId, row: row, column: column))
    }

    private struct ReplyMarkupCache: Codable {
        var order: [String]
        var records: [String: BotReplyMarkupRecord]
    }

    func saveReplyMarkups() {
        let cache = ReplyMarkupCache(order: replyMarkupOrder, records: replyMarkups)
        guard let data = try? JSONEncoder().encode(cache) else { return }
        // Not the Keychain: a keyboard carries no credential (the envelope's
        // `botToken` is an 8-character prefix the server prints for display), and
        // the bot message it belongs to is already on disk in the group store.
        UserDefaults.standard.set(data, forKey: replyMarkupKey)
    }

    func loadReplyMarkups() {
        guard let data = UserDefaults.standard.data(forKey: replyMarkupKey),
              let cache = try? JSONDecoder().decode(ReplyMarkupCache.self, from: data) else { return }
        replyMarkups = cache.records
        replyMarkupOrder = cache.order.filter { cache.records[$0] != nil }
    }

    // MARK: Who is this bot, and do we hold its token?

    /// The bot behind a `bot:` sender key, IF this device owns it.
    ///
    /// Two spellings exist on the wire and both are tried:
    ///   * `bot:<first 8 chars of the token>` — written by `processBotEnvelope`
    ///     from the server's truncated `botToken` field;
    ///   * `bot:<first 8 chars of the bot's UUID>` — written by `emit`, for a bot
    ///     running locally on this device.
    func ownedBot(forSenderKey senderKey: String) -> OSHIBot? {
        guard senderKey.hasPrefix("bot:") else { return nil }
        let raw = String(senderKey.dropFirst(4))
            .replacingOccurrences(of: "...", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        guard !raw.isEmpty else { return nil }
        if let byToken = bots.first(where: { $0.token.lowercased().hasPrefix(raw) }) { return byToken }
        return bots.first(where: { $0.id.uuidString.lowercased().hasPrefix(raw) })
    }

    /// __BOT_PUBLIC_ID_2026_09_16__ How this device proves it may speak to a bot.
    ///
    /// The original shape of `/api/bot/dispatch` took the BOT'S TOKEN, which this
    /// device holds only for bots it created itself — so a button on anybody
    /// else's bot was a dead end, and handing the token to every group member so
    /// it would not be is not a fix: a bearer credential shared with a whole group
    /// is not a credential.
    ///
    /// The server now also accepts `botId` — `sha256("oshi-bot-id:" + token)[:16]`,
    /// a one-way function of the secret — paired with the caller's own public key,
    /// and checks that the caller is a member of a group the bot serves. That is
    /// the right shape: the client authenticates as ITSELF, and the bot is merely
    /// named. So `botId` is preferred wherever it exists, and the token is the
    /// fallback for the one case it still covers.
    enum BotCredential {
        /// `botId` + the caller's own public key. Works for ANY bot whose message
        /// this device actually received.
        case member(botId: String, publicKey: String)
        /// The bot's secret. Only for a bot this device created — the sole path
        /// left for a record that predates `botId`.
        case owner(token: String)
    }

    /// Why a button could not be pressed, in the caller's words.
    /// `Error` so it can be the failure half of a `Result`.
    enum BotCredentialFailure: Error {
        /// No `botId` on the record AND this device does not own the bot. Reachable
        /// only for a keyboard stored before the server published `botId`, or one
        /// that arrived through a path that does not carry it.
        case unidentifiedBot
        /// This device has no identity public key yet.
        case noIdentity
    }

    func credential(for record: BotReplyMarkupRecord,
                    senderKey: String) -> Result<BotCredential, BotCredentialFailure> {
        // `botId` FIRST, even when we own the bot: it is the path the server can
        // authorize on its own terms, and it keeps the secret off the wire.
        if let botId = record.botId?.trimmingCharacters(in: .whitespacesAndNewlines), !botId.isEmpty {
            guard let me = selfPublicKey else { return .failure(.noIdentity) }
            return .success(.member(botId: botId, publicKey: me))
        }
        if let token = ownedBot(forSenderKey: senderKey)?.token {
            return .success(.owner(token: token))
        }
        return .failure(.unidentifiedBot)
    }

    private func message(for failure: BotCredentialFailure) -> String {
        switch failure {
        case .unidentifiedBot: return "bot.button.unidentified_bot".localized
        case .noIdentity: return "bot.button.failed".localized
        }
    }

    private func message(for outcome: BotDispatchOutcome) -> String? {
        switch outcome {
        case .ok: return nil
        case .notMember: return "bot.button.not_member".localized
        case .unknownBot: return "bot.button.unknown_bot".localized
        case .failed: return "bot.button.failed".localized
        }
    }

    /// The local identity's public key — the same one every other outbound path in
    /// the app uses (`UserDefaults "publicKey"`, written by `IdentityManager`), read
    /// here through `MessageGroupManager.currentUserPublicKey` with the live
    /// `IdentityManager` copy as the fallback for the window before it is mirrored.
    private var selfPublicKey: String? {
        if let key = MessageGroupManager.shared.currentUserPublicKey, !key.isEmpty { return key }
        if let key = MessageManager.sharedIdentityManager?.publicKey, !key.isEmpty { return key }
        return nil
    }

    // MARK: Button taps

    /// `callback_data` → POST /api/bot/dispatch (kind `callback_query`).
    ///
    /// The answer does NOT come back on this response: the server replies
    /// `{success, update_id, callback_query_id}` and the bot's own answer arrives
    /// later as a queued `bot_callback_answer`, which `presentCallbackAnswer`
    /// turns into the banner. The spinner therefore clears when the POST
    /// completes, not when the bot has spoken — otherwise a bot that never
    /// answers would leave a button spinning forever.
    func tapCallbackButton(record: BotReplyMarkupRecord,
                           senderKey: String,
                           localMessageId: String,
                           row: Int,
                           column: Int,
                           callbackData: String) {
        let slot = BotManager.slotKey(messageId: localMessageId, row: row, column: column)
        guard !callbacksInFlight.contains(slot) else { return }

        let botName = record.botName ?? "Bot"
        let resolved = credential(for: record, senderKey: senderKey)
        guard case .success(let credential) = resolved else {
            if case .failure(let failure) = resolved {
                botToast = BotToastPayload(text: message(for: failure), botName: botName, isAlert: false)
            }
            return
        }

        callbacksInFlight.insert(slot)
        Task { [weak self] in
            let outcome = await BotManager.postDispatch(
                credential: credential,
                kind: "callback_query",
                groupId: record.groupId,
                messageId: record.sourceMessageId,
                callbackData: callbackData,
                text: nil
            )
            await MainActor.run {
                guard let self else { return }
                self.callbacksInFlight.remove(slot)
                if let text = self.message(for: outcome) {
                    self.botToast = BotToastPayload(text: text, botName: botName, isAlert: false)
                }
            }
        }
    }

    /// `url` → SFSafariViewController. No token, no server round-trip: it is an
    /// ordinary link that happens to be shaped like a button.
    func tapURLButton(_ urlString: String, botName: String) {
        guard BotInlineKeyboard.isOpenableURL(urlString), let url = URL(string: urlString) else {
            botToast = BotToastPayload(text: "bot.button.invalid_link".localized,
                                       botName: botName, isAlert: false)
            return
        }
        botPresentation = BotWebPresentation(url: url, botName: botName, initData: nil)
    }

    /// `web_app` → POST /api/bot/miniapp/session, then open the returned URL in a
    /// `WKWebView` with the signed `initData`.
    func tapWebAppButton(record: BotReplyMarkupRecord,
                         senderKey: String,
                         fallbackURL: String,
                         row: Int,
                         column: Int,
                         localMessageId: String) {
        let slot = BotManager.slotKey(messageId: localMessageId, row: row, column: column)
        guard !callbacksInFlight.contains(slot) else { return }
        let botName = record.botName ?? "Bot"

        let resolved = credential(for: record, senderKey: senderKey)
        guard case .success(let credential) = resolved else {
            if case .failure(let failure) = resolved {
                botToast = BotToastPayload(text: message(for: failure), botName: botName, isAlert: false)
            }
            return
        }

        callbacksInFlight.insert(slot)
        Task { [weak self] in
            let result = await BotManager.postMiniAppSession(credential: credential,
                                                             groupId: record.groupId)
            await MainActor.run {
                guard let self else { return }
                self.callbacksInFlight.remove(slot)
                switch result {
                case .success(let session):
                    // The session's own `url` wins; `fallbackURL` is the button's
                    // declared address, used only when the server answered without
                    // one. Never open the fallback with NO initData pretending to
                    // be a mini app — a page that cannot identify the user is a
                    // plain web page, and it is opened as one.
                    guard let target = session.url ?? URL(string: fallbackURL) else {
                        self.botToast = BotToastPayload(text: "bot.miniapp.open_failed".localized,
                                                        botName: botName, isAlert: false)
                        return
                    }
                    self.botPresentation = BotWebPresentation(url: target,
                                                              botName: botName,
                                                              initData: session.initData)
                case .failure(let outcome):
                    // `notMember` / `unknownBot` say something specific; anything
                    // else is the generic "couldn't open this mini app".
                    let text = (outcome == .failed)
                        ? "bot.miniapp.open_failed".localized
                        : (self.message(for: outcome) ?? "bot.miniapp.open_failed".localized)
                    self.botToast = BotToastPayload(text: text, botName: botName, isAlert: false)
                }
            }
        }
    }

    // MARK: Inbound callback answers

    /// A queued `bot_callback_answer`. `showAlert` decides banner vs modal —
    /// the same split Telegram makes, and the reason the field exists: a bot that
    /// needs an acknowledgement ("are you sure?") cannot use a banner that
    /// disappears on its own.
    func presentCallbackAnswer(text: String, showAlert: Bool, botName: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        // Bounded: this string is rendered by the app, and a bot that sends a
        // 200 KB "toast" must not be able to paint over the whole conversation.
        let clipped = String(trimmed.prefix(400))
        let payload = BotToastPayload(text: clipped, botName: botName, isAlert: showAlert)
        if showAlert { botAlert = payload } else { botToast = payload }
    }

    // MARK: Transport

    /// __BOT_PUBLIC_ID_2026_09_16__ The credential's own fields, and the name the
    /// endpoint gives the caller's public key: `fromPublicKey` for dispatch,
    /// `userPublicKey` for the mini-app session. One place builds both, so the two
    /// calls cannot drift into disagreeing about who is asking.
    private static func authFields(_ credential: BotCredential,
                                   publicKeyField: String) -> [String: Any] {
        switch credential {
        case .member(let botId, let publicKey):
            return ["botId": botId, publicKeyField: publicKey]
        case .owner(let token):
            return ["token": token]
        }
    }

    /// Ed25519 request signature (the `/v2/*` key, `CallPushSigner`), sent;
    /// on `401 signature_required` signed again (fresh timestamp) and retried
    /// ONCE. A 403 is returned as is — never looped on.
    static func signedSend(_ unsigned: URLRequest,
                           transport: (URLRequest) async throws -> (Data, URLResponse) = { try await URLSession.shared.data(for: $0) },
                           sign: (inout URLRequest) -> Bool = { CallPushSigner.sign(&$0) }) async throws -> (Data, URLResponse) {
        var request = unsigned
        _ = sign(&request)
        let first = try await transport(request)
        guard (first.1 as? HTTPURLResponse)?.statusCode == 401 else { return first }
        var again = unsigned
        guard sign(&again) else { return first }
        return try await transport(again)
    }

    private static func postDispatch(credential: BotCredential,
                                     kind: String,
                                     groupId: String,
                                     messageId: String,
                                     callbackData: String?,
                                     text: String?) async -> BotDispatchOutcome {
        var payload: [String: Any] = [
            "kind": kind,
            "groupId": groupId,
            "messageId": messageId
        ]
        for (key, value) in authFields(credential, publicKeyField: "fromPublicKey") {
            payload[key] = value
        }
        if let callbackData { payload["callbackData"] = callbackData }
        if let text { payload["text"] = text }

        guard let url = URL(string: "\(platformBaseURL)/api/bot/dispatch"),
              let body = try? JSONSerialization.data(withJSONObject: payload) else { return .failed }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        request.timeoutInterval = 15

        do {
            // __BOTS_PHASE1_2026_11_24__ signed (contract "night" amendment):
            // identity = fromPublicKey; a 401 is re-signed and retried once.
            let (data, response) = try await signedSend(request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            // 403 and 404 are the server telling us something a user can act on —
            // you are not in a group this bot serves, or this bot is gone. Folding
            // them into a generic "try again" would send people to retry a button
            // that will never work.
            if status == 403 { return .notMember }
            if status == 404 { return .unknownBot }
            guard (200..<300).contains(status) else {
                OshiLog.ai.info("🤖 bot dispatch \(kind) HTTP \(status)")
                return .failed
            }
            let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            // `success: false` with HTTP 200 is a real answer shape here, so the
            // status code alone is not the verdict.
            if let success = json?["success"] as? Bool { return success ? .ok : .failed }
            return .ok
        } catch {
            OshiLog.ai.info("⚠️ bot dispatch failed: \(error.localizedDescription)")
            return .failed
        }
    }

    struct MiniAppSession {
        var url: URL?
        var initData: String?
    }

    private static func postMiniAppSession(credential: BotCredential,
                                           groupId: String) async -> Result<MiniAppSession, BotDispatchOutcome> {
        var payload: [String: Any] = ["groupId": groupId]
        for (key, value) in authFields(credential, publicKeyField: "userPublicKey") {
            payload[key] = value
        }
        guard let url = URL(string: "\(platformBaseURL)/api/bot/miniapp/session"),
              let body = try? JSONSerialization.data(withJSONObject: payload) else { return .failure(.failed) }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        request.timeoutInterval = 15

        do {
            // __BOTS_PHASE1_2026_11_24__ signed, identity = userPublicKey.
            let (data, response) = try await signedSend(request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            // Same two precise answers as dispatch — see `BotDispatchOutcome`.
            if status == 403 { return .failure(.notMember) }
            if status == 404 { return .failure(.unknownBot) }
            guard (200..<300).contains(status),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                OshiLog.ai.info("🤖 miniapp session HTTP \(status)")
                return .failure(.failed)
            }
            if let success = json["success"] as? Bool, !success { return .failure(.failed) }
            let urlString = json["url"] as? String
            let target = urlString.flatMap { BotInlineKeyboard.isOpenableURL($0) ? URL(string: $0) : nil }
            let initData = json["initData"] as? String
            guard target != nil || initData != nil else { return .failure(.failed) }
            return .success(MiniAppSession(url: target, initData: initData))
        } catch {
            OshiLog.ai.info("⚠️ miniapp session failed: \(error.localizedDescription)")
            return .failure(.failed)
        }
    }
}
