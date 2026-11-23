package com.oshi.messenger.data.model

import androidx.room.*

/**
 * OSHI Bot Models - Matching iOS OSHIBot, BotTrigger, BotPermissions
 * Telegram BotFather-like bot system for OSHI Messenger
 */

@Entity(tableName = "bots")
data class OSHIBot(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val token: String,
    val name: String,
    val username: String,           // @username
    val description: String = "",
    val avatarEmoji: String = "🤖",
    val type: String = "automation", // BotType as string for Room
    val isEnabled: Boolean = true,
    val permissions: String = "",   // JSON serialized BotPermissions
    val webhookURL: String? = null,
    val scriptContent: String? = null,
    val assignedGroups: String = "", // Comma-separated group UUIDs
    val createdAt: Long = System.currentTimeMillis(),
    val lastActivity: Long? = null,
    val messagesSent: Int = 0,
    val ownerPublicKey: String
)

@Entity(
    tableName = "bot_triggers",
    foreignKeys = [ForeignKey(
        entity = OSHIBot::class,
        parentColumns = ["id"],
        childColumns = ["botId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("botId")]
)
data class BotTrigger(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val botId: String,
    val type: String = "keyword",   // TriggerType as string for Room
    val condition: String,          // Keyword, cron expression, regex, etc.
    val responseTemplate: String,
    val isActive: Boolean = true
)

@Entity(
    tableName = "bot_messages",
    foreignKeys = [ForeignKey(
        entity = OSHIBot::class,
        parentColumns = ["id"],
        childColumns = ["botId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("botId")]
)
data class BotMessage(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val botId: String,
    val groupId: String? = null,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFromBot: Boolean = true
)

enum class BotType(val displayName: String, val emoji: String, val description: String) {
    AUTOMATION("Automation", "⚡", "Responds to triggers and keywords"),
    WEBHOOK("Webhook", "🔗", "HTTP webhook-driven bot"),
    SCHEDULED("Scheduled", "⏰", "Sends messages on a schedule"),
    MODERATOR("Moderator", "🛡️", "Group moderation and rules")
}

enum class TriggerType(val displayName: String) {
    KEYWORD("Keyword"),
    COMMAND("Command"),
    SCHEDULE("Schedule"),
    MEMBER_JOIN("Member Join"),
    MEMBER_LEAVE("Member Leave"),
    REGEX("Regex")
}

data class BotPermissions(
    val canSendMessages: Boolean = true,
    val canReadMessages: Boolean = true,
    val canSendMedia: Boolean = false,
    val canPinMessages: Boolean = false,
    val canManageMembers: Boolean = false
) {
    fun toJson(): String {
        return "{\"canSendMessages\":$canSendMessages,\"canReadMessages\":$canReadMessages," +
               "\"canSendMedia\":$canSendMedia,\"canPinMessages\":$canPinMessages," +
               "\"canManageMembers\":$canManageMembers}"
    }

    companion object {
        fun fromJson(json: String): BotPermissions {
            if (json.isEmpty()) return BotPermissions()
            return try {
                val send = json.contains("\"canSendMessages\":true")
                val read = json.contains("\"canReadMessages\":true")
                val media = json.contains("\"canSendMedia\":true")
                val pin = json.contains("\"canPinMessages\":true")
                val manage = json.contains("\"canManageMembers\":true")
                BotPermissions(send, read, media, pin, manage)
            } catch (e: Exception) {
                BotPermissions()
            }
        }
    }
}

data class BotTemplate(
    val id: String,
    val name: String,
    val description: String,
    val emoji: String,
    val type: BotType,
    val triggers: List<BotTrigger> = emptyList(),
    val scriptTemplate: String? = null
) {
    companion object {
        val TEMPLATES = listOf(
            BotTemplate(
                id = "welcome",
                name = "Welcome Bot",
                description = "Greets new members when they join",
                emoji = "👋",
                type = BotType.MODERATOR,
                triggers = listOf(
                    BotTrigger(
                        botId = "",
                        type = "member_join",
                        condition = "[MEMBER_JOIN]",
                        responseTemplate = "Welcome to the group, {{member}}! 🎉"
                    )
                )
            ),
            BotTemplate(
                id = "faq",
                name = "FAQ Bot",
                description = "Answers frequently asked questions",
                emoji = "❓",
                type = BotType.AUTOMATION,
                triggers = listOf(
                    BotTrigger(
                        botId = "",
                        type = "command",
                        condition = "/help",
                        responseTemplate = "Available commands:\n/help - Show this message\n/about - About this group"
                    )
                )
            ),
            BotTemplate(
                id = "reminder",
                name = "Reminder Bot",
                description = "Sends scheduled reminders",
                emoji = "⏰",
                type = BotType.SCHEDULED,
                triggers = listOf(
                    BotTrigger(
                        botId = "",
                        type = "schedule",
                        condition = "0 9 * * *",
                        responseTemplate = "Good morning! Don't forget to check today's tasks. 📋"
                    )
                )
            ),
            BotTemplate(
                id = "moderator",
                name = "Moderator Bot",
                description = "Enforces group rules",
                emoji = "🛡️",
                type = BotType.MODERATOR
            ),
            BotTemplate(
                id = "webhook",
                name = "Webhook Bot",
                description = "Receives and forwards webhook events",
                emoji = "🔗",
                type = BotType.WEBHOOK
            ),
            BotTemplate(
                id = "echo",
                name = "Echo Bot",
                description = "Echoes messages for testing",
                emoji = "🔊",
                type = BotType.AUTOMATION,
                triggers = listOf(
                    BotTrigger(
                        botId = "",
                        type = "keyword",
                        condition = "echo",
                        responseTemplate = "Echo: {{message}}"
                    )
                )
            )
        )
    }
}
