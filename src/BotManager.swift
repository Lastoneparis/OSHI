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
    let token: String          // Unique bot token (like Telegram bot token)
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

    private let storageKey = "oshi_bots_v1"
    private let messagesKey = "oshi_bot_messages_v1"
    private var scheduledTimers: [UUID: Timer] = [:]

    private init() {
        loadBots()
        startScheduledBots()
    }

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

    // MARK: - Post as Bot

    func postAsBot(_ botId: UUID, toGroup groupId: UUID, content: String) {
        guard let idx = bots.firstIndex(where: { $0.id == botId }) else { return }
        let bot = bots[idx]
        guard bot.isEnabled, bot.permissions.canSendMessages else { return }

        recordBotMessage(botId: botId, groupId: groupId, content: content, isFromBot: true)
        NotificationCenter.default.post(
            name: NSNotification.Name("BotResponse"),
            object: nil,
            userInfo: [
                "botId": bot.id.uuidString,
                "botName": bot.name,
                "groupId": groupId.uuidString,
                "content": content
            ]
        )
        bots[idx].messagesSent += 1
        bots[idx].lastActivity = Date()
        saveBots()
    }

    // MARK: - Trigger Processing

    func processMessage(content: String, groupId: UUID, senderKey: String) {
        let assignedBots = botsForGroup(groupId)
        for bot in assignedBots {
            guard bot.isEnabled, bot.permissions.canReadMessages else { continue }
            for trigger in bot.triggers where trigger.isActive {
                if shouldFireTrigger(trigger, for: content) {
                    let response = generateResponse(trigger: trigger, bot: bot, input: content)
                    recordBotMessage(botId: bot.id, groupId: groupId, content: response, isFromBot: true)
                    // Post notification so GroupMessaging can pick it up
                    NotificationCenter.default.post(
                        name: NSNotification.Name("BotResponse"),
                        object: nil,
                        userInfo: [
                            "botId": bot.id.uuidString,
                            "botName": bot.name,
                            "groupId": groupId.uuidString,
                            "content": response
                        ]
                    )
                    // Update stats
                    if let idx = bots.firstIndex(where: { $0.id == bot.id }) {
                        bots[idx].messagesSent += 1
                        bots[idx].lastActivity = Date()
                    }
                }
            }
        }
        saveBots()
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
                            let response = self.generateResponse(trigger: trigger, bot: bot, input: "")
                            self.recordBotMessage(botId: bot.id, groupId: groupId, content: response, isFromBot: true)
                            NotificationCenter.default.post(
                                name: NSNotification.Name("BotResponse"),
                                object: nil,
                                userInfo: [
                                    "botId": bot.id.uuidString,
                                    "botName": bot.name,
                                    "groupId": groupId.uuidString,
                                    "content": response
                                ]
                            )
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
                    self.recordBotMessage(botId: bot.id, groupId: gid, content: responseText, isFromBot: true)
                    NotificationCenter.default.post(
                        name: NSNotification.Name("BotResponse"),
                        object: nil,
                        userInfo: [
                            "botId": bot.id.uuidString,
                            "botName": bot.name,
                            "groupId": groupId,
                            "content": responseText
                        ]
                    )
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

    /// Register bot with OSHI server for external API access (Python, curl, etc.)
    func registerBotWithServer(_ bot: OSHIBot) {
        Task {
            let groups: [[String: Any]] = bot.assignedGroups.compactMap { groupId in
                guard let group = MessageGroupManager.shared.groups.first(where: { $0.id == groupId }) else { return nil }
                return [
                    "id": groupId.uuidString,
                    "name": group.name,
                    "members": group.members.map { $0.publicKey }
                ]
            }

            let payload: [String: Any] = [
                "token": bot.token,
                "botName": bot.name,
                "ownerPublicKey": bot.ownerPublicKey,
                "groups": groups
            ]

            guard let url = URL(string: "https://oshi-messenger.com/api/bot/register"),
                  let body = try? JSONSerialization.data(withJSONObject: payload) else { return }

            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
            request.timeoutInterval = 10

            do {
                let (data, response) = try await URLSession.shared.data(for: request)
                if let httpResponse = response as? HTTPURLResponse {
                    print("🤖 Bot server register: \(httpResponse.statusCode)")
                    if let result = String(data: data, encoding: .utf8) {
                        print("   Response: \(result)")
                    }
                }
            } catch {
                print("⚠️ Bot server register failed: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Persistence

    private func saveBots() {
        if let data = try? JSONEncoder().encode(bots) {
            UserDefaults.standard.set(data, forKey: storageKey)
        }
    }

    private func loadBots() {
        if let data = UserDefaults.standard.data(forKey: storageKey),
           let decoded = try? JSONDecoder().decode([OSHIBot].self, from: data) {
            bots = decoded
        }
        if let msgData = UserDefaults.standard.data(forKey: messagesKey),
           let decoded = try? JSONDecoder().decode([UUID: [BotMessage]].self, from: msgData) {
            botMessages = decoded
        }
    }

    private func saveMessages() {
        if let data = try? JSONEncoder().encode(botMessages) {
            UserDefaults.standard.set(data, forKey: messagesKey)
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
            name: "Welcome Bot",
            description: "Greets new members when they join a group",
            icon: "hand.wave.fill",
            type: .moderator,
            triggers: [
                BotTrigger(
                    id: UUID(),
                    type: .memberJoin,
                    condition: "member_join",
                    responseTemplate: "Welcome to the group! 👋 Please read the pinned rules.",
                    isActive: true
                )
            ],
            scriptTemplate: nil
        ),
        BotTemplate(
            id: UUID(),
            name: "FAQ Bot",
            description: "Answers frequently asked questions via /help command",
            icon: "questionmark.circle.fill",
            type: .automation,
            triggers: [
                BotTrigger(
                    id: UUID(),
                    type: .command,
                    condition: "/help",
                    responseTemplate: "Available commands:\n/help - Show this message\n/rules - Show group rules\n/about - About this group",
                    isActive: true
                )
            ],
            scriptTemplate: nil
        ),
        BotTemplate(
            id: UUID(),
            name: "Reminder Bot",
            description: "Sends scheduled messages at regular intervals",
            icon: "bell.fill",
            type: .scheduled,
            triggers: [
                BotTrigger(
                    id: UUID(),
                    type: .schedule,
                    condition: "86400",
                    responseTemplate: "Daily reminder: Stay secure! 🔒 ({{date}})",
                    isActive: true
                )
            ],
            scriptTemplate: nil
        ),
        BotTemplate(
            id: UUID(),
            name: "Webhook Bot",
            description: "Connect to external services via HTTP webhooks",
            icon: "network",
            type: .webhook,
            triggers: [],
            scriptTemplate: nil
        ),
        BotTemplate(
            id: UUID(),
            name: "Echo Bot",
            description: "Echoes back messages containing a keyword",
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
            name: "Stats Bot",
            description: "Provides group activity statistics on command",
            icon: "chart.bar.fill",
            type: .automation,
            triggers: [
                BotTrigger(
                    id: UUID(),
                    type: .command,
                    condition: "/stats",
                    responseTemplate: "📊 Group Stats ({{date}}):\nBot {{bot_name}} is active and monitoring.",
                    isActive: true
                )
            ],
            scriptTemplate: nil
        ),
    ]
}
