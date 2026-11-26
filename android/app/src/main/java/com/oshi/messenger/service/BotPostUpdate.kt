package com.oshi.messenger.service

import com.oshi.messenger.data.model.GroupMessage
import org.json.JSONObject

/**
 * __BOTS_PHASE1_2026_11_24__ A bot acts on ITS OWN post inside a group or channel
 * (Telegram's `editMessageText` / `editMessageReplyMarkup` / `deleteMessage` /
 * `pinChatMessage`): the post a member is looking at changes in place — live vote
 * counts, "Registered ✓".
 *
 * Wire (server amendment S5): a bot queue item, sealed like a bot message, outer
 * `type:"bot_message_action"`; the opened inner payload is
 * ```
 * {type:"bot_message_action", action:"edit"|"delete"|"pin"|"unpin", messageId:<envId>,
 *  targetMessageId:<original messageId>, groupId, botId, content?, replyMarkup?, editedAt?}
 * ```
 * For `edit`: `content` present = new text; `replyMarkup` key present = new keyboard
 * (`null` removes it); key absent = unchanged.
 *
 * Applied only to the row whose id is `targetMessageId` AND whose `botId` is this
 * bot's (S5). Message ids are visible to every member: an action naming somebody
 * else's message — a person's, another bot's — is dropped.
 */
data class BotPostUpdate(
    val action: Action,
    val groupId: String,
    val targetMessageId: String,
    val botId: String,
    val newContent: String?,
    /** The new keyboard JSON; null with [clearMarkup] false = keep the current one. */
    val newReplyMarkup: String?,
    val clearMarkup: Boolean,
) {
    enum class Action { EDIT, DELETE, PIN, UNPIN }

    companion object {
        const val TYPE = "bot_message_action"

        fun isUpdateType(type: String): Boolean = type == TYPE

        /** Null when the item lacks what an action needs. [outerBotId] wins over the inner one. */
        @JvmStatic
        fun parse(json: JSONObject, outerBotId: String? = null): BotPostUpdate? {
            if (json.optString("type", "") != TYPE) return null
            val action = when (json.optString("action", "")) {
                "edit" -> Action.EDIT
                "delete" -> Action.DELETE
                "pin" -> Action.PIN
                "unpin" -> Action.UNPIN
                else -> return null
            }
            val groupId = json.optString("groupId", "").trim()
            val target = json.optString("targetMessageId", "").trim()
            val botId = (outerBotId?.takeIf { it.isNotBlank() } ?: json.optString("botId", "")).trim()
            if (groupId.isEmpty() || target.isEmpty() || botId.isEmpty()) return null
            val content = if (json.has("content") && !json.isNull("content")) json.optString("content").take(4096) else null
            val markupPresent = json.has("replyMarkup")
            val markup = json.optJSONObject("replyMarkup")?.toString()
            if (action == Action.EDIT && content == null && !markupPresent) return null
            return BotPostUpdate(
                action = action,
                groupId = groupId,
                targetMessageId = target,
                botId = botId,
                newContent = content,
                newReplyMarkup = markup,
                clearMarkup = markupPresent && markup == null,
            )
        }

        /** The bot's own post [update] targets, or null. */
        @JvmStatic
        fun target(messages: List<GroupMessage>, update: BotPostUpdate): GroupMessage? =
            messages.firstOrNull { it.id.equals(update.targetMessageId, ignoreCase = true) }
                ?.takeIf { isAuthoredBy(it, update) }

        /**
         * The thread after an EDIT or DELETE, or null when nothing applies (unknown id, not
         * this bot's post, already deleted, no change, or a pin action).
         */
        @JvmStatic
        fun applyTo(messages: List<GroupMessage>, update: BotPostUpdate): List<GroupMessage>? {
            if (update.action != Action.EDIT && update.action != Action.DELETE) return null
            val t = target(messages, update) ?: return null
            if (t.isDeleted) return null
            val replaced = if (update.action == Action.DELETE) {
                t.copy(isDeleted = true, content = "", replyMarkup = null)
            } else {
                t.copy(
                    content = update.newContent ?: t.content,
                    replyMarkup = when {
                        update.clearMarkup -> null
                        update.newReplyMarkup != null -> update.newReplyMarkup
                        else -> t.replyMarkup
                    },
                )
            }
            // Field by field: GroupMessage.equals compares ids only (ByteArray fields).
            if (replaced.content == t.content && replaced.replyMarkup == t.replyMarkup && replaced.isDeleted == t.isDeleted) return null
            val index = messages.indexOf(t)
            return messages.toMutableList().also { it[index] = replaced }
        }

        @JvmStatic
        fun isAuthoredBy(target: GroupMessage, update: BotPostUpdate): Boolean =
            target.senderPublicKey.startsWith("bot:") &&
                target.botId?.equals(update.botId, ignoreCase = true) == true
    }
}
