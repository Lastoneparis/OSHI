package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import com.oshi.messenger.BuildConfig
import com.oshi.messenger.data.local.AppDatabase
import com.oshi.messenger.data.model.*
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.vps.VPSClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BotManager - Android equivalent of iOS BotManager.swift
 *
 * Manages bot lifecycle:
 * - CRUD operations (create, read, update, delete)
 * - Trigger evaluation (keyword, command, regex, member events)
 * - Server registration via existing Bot API (port 8081)
 * - Scheduled bot execution
 * - Token generation
 */
@Singleton
class BotManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val vpsClient: VPSClient,
    private val cryptoManager: CryptoManager,
    private val groupManager: GroupManager
) {
    companion object {
        private const val TAG = "BotManager"
        private const val BOT_API_BASE = "/api/bot"

        /**
         * Substitute template variables in a trigger's response.
         *
         * iOS `generateResponse` (BotManager.swift:330-338) defines the canonical
         * set: `{{input}}`, `{{bot_name}}`, `{{date}}`, `{{timestamp}}`. Android
         * used `{{message}}`, `{{sender}}`, `{{member}}` — ZERO overlap, so a
         * template written on one platform rendered its own placeholders
         * literally on the other. The iOS names are now authoritative; the three
         * Android names are kept as aliases so templates already saved on Android
         * keep working.
         */
        @JvmStatic
        fun renderTemplate(
            template: String,
            botName: String,
            input: String,
            senderKey: String,
            nowMillis: Long
        ): String {
            val shortSender = senderKey.take(8)
            val dateText = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(nowMillis))
            return template
                // iOS variable set — authoritative
                .replace("{{input}}", input)
                .replace("{{bot_name}}", botName)
                .replace("{{date}}", dateText)
                .replace("{{timestamp}}", (nowMillis / 1000).toString())
                // Android legacy aliases
                .replace("{{message}}", input)
                .replace("{{sender}}", shortSender)
                .replace("{{member}}", shortSender)
        }

        // =====================================================================
        // __BOT_IN_THE_PIPELINE_2026_11_09__ The rules below are pure so they are
        // asserted by JVM tests (BotPipelineRulesTest) rather than assumed. Each
        // one is a port of an iOS decision in `OSHI/BotManager.swift`.
        // =====================================================================

        /** iOS `BotLimits` (BotManager.swift:149-154). Bandwidth sharing on LoRa, not anti-abuse. */
        const val REPLIES_PER_MINUTE = 6
        const val MAX_REPLY_CHARS = 2000

        /**
         * iOS `shouldEvaluateTriggers` (swift:711-717), plus [botAuthored]: a bot's
         * output never fires a trigger, or two bots with crossing keywords answer
         * each other forever. iOS recognises bot output by its `bot:` sender; an
         * Android bot reply travels under its owner's AUTHENTICATED key (see
         * [GroupManager.sendBotMessage]) and is recognised by the wire's
         * `botAuthor` field instead. Control payloads (receipts, typing,
         * reactions, ballots) are plumbing, not prose.
         */
        @JvmStatic
        fun shouldEvaluateTriggers(senderKey: String, content: String, botAuthored: Boolean): Boolean {
            if (botAuthored) return false
            if (senderKey.startsWith("bot:")) return false
            val trimmed = content.trim()
            if (trimmed.isEmpty()) return false
            if (OSHIControlPayload.isControl(trimmed)) return false
            return true
        }

        /**
         * Only the device that OWNS a bot runs it. Bots live in this device's Room
         * store, so this is a second lock, against a bot row that reached a device
         * under another identity (restore, devsync). A blank owner is a legacy row
         * created before the field was filled, and stays runnable.
         */
        @JvmStatic
        fun isOwnedBy(ownerPublicKey: String, myPublicKey: String): Boolean {
            if (myPublicKey.isBlank()) return false
            if (ownerPublicKey.isBlank()) return true
            return GroupUpdateAuthorizer.canonicalIdentity(ownerPublicKey) ==
                GroupUpdateAuthorizer.canonicalIdentity(myPublicKey)
        }

        /** iOS `shouldFireTrigger` (swift:762-782), same case rules. */
        @JvmStatic
        fun triggerMatches(type: String, condition: String, content: String): Boolean = when (type) {
            "keyword" -> condition.isNotEmpty() && content.contains(condition, ignoreCase = true)
            "command" -> {
                val raw = condition.trim()
                if (raw.isEmpty() || raw == "/") false else {
                    val cmd = if (raw.startsWith("/")) raw else "/$raw"
                    content.trim().lowercase().startsWith(cmd.lowercase())
                }
            }
            "regex" -> try {
                Regex(condition, RegexOption.IGNORE_CASE).containsMatchIn(content)
            } catch (_: Exception) { false }
            "member_join" -> content.contains("[MEMBER_JOIN]")
            "member_leave" -> content.contains("[MEMBER_LEAVE]")
            else -> false // "schedule" runs on its timer
        }

        data class EmitDecision(val allow: Boolean, val content: String, val reason: String)

        /**
         * iOS `authorizeEmit` (swift:518-546, __BOT_SCOPES_ENFORCED_2026_08_16__):
         * write scope, media scope, a 60 s sliding window, and truncation rather
         * than refusal for a long reply. The clock is a parameter so the limiter
         * is testable.
         */
        @JvmStatic
        fun authorizeEmit(
            canSendMessages: Boolean,
            canSendMedia: Boolean,
            hasMedia: Boolean,
            content: String,
            recentReplies: List<Long>,
            nowMillis: Long
        ): EmitDecision {
            if (!canSendMessages) return EmitDecision(false, content, "write-scope-none")
            if (hasMedia && !canSendMedia) return EmitDecision(false, content, "media-scope-denied")
            val inWindow = recentReplies.count { it > nowMillis - 60_000L }
            if (inWindow >= REPLIES_PER_MINUTE) return EmitDecision(false, content, "rate-limited")
            if (content.length > MAX_REPLY_CHARS) {
                return EmitDecision(true, content.take(MAX_REPLY_CHARS), "truncated")
            }
            return EmitDecision(true, content, "ok")
        }

        /** iOS `emit` (swift:627): the label every renderer keys off with `startsWith("bot:")`. */
        @JvmStatic
        fun botSenderKey(botId: String): String = "bot:" + botId.take(8)

        /** iOS `emit` (swift:630): the body a bot reply carries, `[Name] text`. */
        @JvmStatic
        fun botWireContent(botName: String, content: String): String = "[$botName] $content"

        /** iOS `looksLikeACommand` (swift:655-660): `/` then a letter, nothing looser. */
        @JvmStatic
        fun looksLikeACommand(text: String): Boolean {
            val t = text.trim()
            return t.length > 1 && t[0] == '/' && t[1].isLetter()
        }

        /**
         * Interval of a `schedule` trigger. A bare number is SECONDS with a 60 s
         * floor, as on iOS (swift:806-807) — Android read it as milliseconds, so a
         * bot written "3600" on an iPhone would have posted every 3.6 s. Anything
         * unparseable (the template's cron "0 9 * * *") runs NOTHING, as on iOS,
         * instead of silently becoming hourly.
         */
        @JvmStatic
        fun scheduleIntervalMs(condition: String): Long? = when (condition.trim().lowercase()) {
            "every_15min" -> 900_000L
            "every_30min" -> 1_800_000L
            "every_hour" -> 3_600_000L
            "every_6h" -> 21_600_000L
            "every_day" -> 86_400_000L
            else -> condition.trim().toDoubleOrNull()?.takeIf { it > 0 }
                ?.let { maxOf(it, 60.0).times(1000).toLong() }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val scheduledJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** botId → reply times, for the sliding window. In memory, like iOS `recentReplyTimes`. */
    private val recentReplyTimes = java.util.concurrent.ConcurrentHashMap<String, List<Long>>()

    // Public state
    val bots: Flow<List<OSHIBot>> = database.botDao().getAllBots()

    init {
        // __BOT_IN_THE_PIPELINE_2026_11_09__ The group manager has had this hook
        // since the bot port, and nobody ever set it: `processMessage` had no
        // caller, so a `/help` typed in a group reached no trigger, on either
        // side. GroupManager calls it on the outbound send AND on the inbound
        // receive, exactly where iOS calls `processMessage` (GroupMessaging.swift
        // :2684 and :3723). A callback rather than an injection because
        // GroupManager is built before anything that needs bots.
        groupManager.onGroupMessageForBots = { groupId, content, senderKey, botAuthored ->
            scope.launch {
                runCatching { processMessage(content, groupId, senderKey, botAuthored) }
                    .onFailure { Log.w(TAG, "processMessage failed: ${it.message}") }
            }
        }
        // Re-register every bot with the server on launch so its group member
        // lists stay current. iOS does this from its own init
        // (BotManager.swift:134 → syncAllBotsWithServer :138), after a short
        // delay so the group store has finished loading.
        syncAllBotsWithServer()
        // iOS starts its scheduled bots from init (swift:321); Android only ever
        // started one after an edit, so a reminder bot was dead after a restart.
        scope.launch {
            runCatching {
                database.botDao().getAllBotsList()
                    .filter { it.type == "scheduled" && it.isEnabled }
                    .forEach { restartScheduledBot(it) }
            }
        }
    }

    /**
     * Re-register all bots that are assigned to at least one group.
     * iOS `syncAllBotsWithServer()` (BotManager.swift:137-149).
     */
    fun syncAllBotsWithServer() {
        scope.launch {
            delay(2_000)
            try {
                val all = database.botDao().getAllBotsList()
                    .filter { it.assignedGroups.split(",").any { g -> g.isNotEmpty() } }
                for (bot in all) registerBotWithServer(bot)
                if (all.isNotEmpty()) Log.d(TAG, "🤖 Auto-synced ${all.size} bot(s) with server")
            } catch (e: Exception) {
                Log.w(TAG, "syncAllBotsWithServer failed: ${e.message}")
            }
        }
    }

    /**
     * Re-register every bot assigned to a group — call when membership changes.
     * iOS `syncBotsForGroup(_:)` (BotManager.swift:484-493).
     */
    fun syncBotsForGroup(groupId: String) {
        scope.launch {
            try {
                val affected = database.botDao().getAllBotsList()
                    .filter { it.assignedGroups.split(",").contains(groupId) }
                for (bot in affected) registerBotWithServer(bot)
                if (affected.isNotEmpty()) {
                    Log.d(TAG, "🤖 Synced ${affected.size} bot(s) for group ${groupId.take(8)}…")
                }
            } catch (e: Exception) {
                Log.w(TAG, "syncBotsForGroup failed: ${e.message}")
            }
        }
    }

    // =========================================================================
    // BOT CRUD
    // =========================================================================

    suspend fun createBot(
        name: String,
        username: String,
        description: String,
        type: BotType,
        ownerPublicKey: String
    ): OSHIBot {
        val token = generateBotToken()
        val bot = OSHIBot(
            token = token,
            name = name,
            username = username,
            description = description,
            type = type.name.lowercase(),
            ownerPublicKey = ownerPublicKey,
            permissions = BotPermissions().toJson()
        )
        database.botDao().insertBot(bot)
        Log.d(TAG, "Created bot: ${bot.name} (@${bot.username}), token=${bot.token.take(8)}...")
        return bot
    }

    suspend fun deleteBot(bot: OSHIBot) {
        scheduledJobs[bot.id]?.cancel()
        scheduledJobs.remove(bot.id)
        database.botDao().deleteBot(bot.id)
        // Revoke the server-side token. iOS `deleteBot` ends with
        // `unregisterBotFromServer(bot)` (BotManager.swift:179-186) for a reason:
        // the token is a bearer credential for the external Bot API, and deleting
        // only the local row left it live on the server forever — a deleted bot
        // could still post into the user's groups.
        runCatching { unregisterBotFromServer(bot) }
            .onFailure { Log.w(TAG, "Bot token revoke failed for ${bot.name}: ${it.message}") }
        Log.d(TAG, "Deleted bot: ${bot.name}")
    }

    suspend fun updateBot(bot: OSHIBot) {
        database.botDao().updateBot(bot)
        // Restart scheduled bot if needed
        if (bot.type == "scheduled" && bot.isEnabled) {
            restartScheduledBot(bot)
        }
    }

    suspend fun toggleBot(botId: String) {
        val bot = database.botDao().getBotById(botId) ?: return
        val updated = bot.copy(isEnabled = !bot.isEnabled)
        database.botDao().updateBot(updated)
        if (!updated.isEnabled) {
            scheduledJobs[botId]?.cancel()
        } else if (updated.type == "scheduled") {
            restartScheduledBot(updated) // iOS toggleBot restarts it (swift:474-475)
        }
    }

    // =========================================================================
    // TRIGGERS
    // =========================================================================

    suspend fun addTrigger(botId: String, trigger: BotTrigger) {
        val t = trigger.copy(botId = botId)
        database.botDao().insertTrigger(t)
    }

    suspend fun removeTrigger(triggerId: String) {
        database.botDao().deleteTrigger(triggerId)
    }

    suspend fun updateTrigger(trigger: BotTrigger) {
        database.botDao().insertTrigger(trigger)
    }

    fun getTriggersForBot(botId: String): Flow<List<BotTrigger>> {
        return database.botDao().getTriggersForBot(botId)
    }

    // =========================================================================
    // GROUP ASSIGNMENT
    // =========================================================================

    suspend fun assignBotToGroup(botId: String, groupId: String) {
        val bot = database.botDao().getBotById(botId) ?: return
        val groups = bot.assignedGroups.split(",").filter { it.isNotEmpty() }.toMutableList()
        if (!groups.contains(groupId)) {
            groups.add(groupId)
            database.botDao().updateBot(bot.copy(assignedGroups = groups.joinToString(",")))
        }
    }

    suspend fun unassignBotFromGroup(botId: String, groupId: String) {
        val bot = database.botDao().getBotById(botId) ?: return
        val groups = bot.assignedGroups.split(",").filter { it.isNotEmpty() && it != groupId }
        database.botDao().updateBot(bot.copy(assignedGroups = groups.joinToString(",")))
    }

    // =========================================================================
    // MESSAGE PROCESSING (Trigger Evaluation)
    // =========================================================================

    /**
     * Evaluate every bot this device owns in [groupId] against one message.
     *
     * __BOT_IN_THE_PIPELINE_2026_11_09__ Called by [GroupManager] on both the
     * message this device sends and the messages it receives (iOS
     * `processMessage`, swift:726-760). [botAuthored] is the wire's `botAuthor`
     * flag: a reply another bot already made must not fire this one.
     */
    suspend fun processMessage(
        content: String,
        groupId: String,
        senderKey: String,
        botAuthored: Boolean = false
    ) {
        if (!shouldEvaluateTriggers(senderKey, content, botAuthored)) return
        val me = cryptoManager.getPublicKeyString()
        val usable = database.botDao().getAllBotsList().filter { bot ->
            bot.isEnabled &&
                assignedGroupIds(bot).any { it.equals(groupId, ignoreCase = true) } &&
                isOwnedBy(bot.ownerPublicKey, me) &&
                getPermissions(bot).canReadMessages
        }
        if (usable.isEmpty()) return

        var fired = 0
        for (bot in usable) {
            for (trigger in database.botDao().getTriggersForBotList(bot.id)) {
                if (!trigger.isActive || !triggerMatches(trigger.type, trigger.condition, content)) continue
                fired++
                if (bot.type == "webhook" && !bot.webhookURL.isNullOrEmpty()) {
                    scope.launch { fireWebhook(bot, trigger.condition, content, senderKey, groupId) }
                } else {
                    emit(bot.id, groupId, renderTemplate(trigger.responseTemplate, bot, content, senderKey))
                }
            }
        }
        if (fired == 0) Log.d(TAG, "🤖 no trigger matched in ${groupId.take(8)} (${usable.size} bot(s))")
    }

    /**
     * Is there a bot in this group that could actually answer? iOS
     * `hasUsableBot` (swift:663-665), plus the owner rule [processMessage] applies.
     */
    suspend fun hasUsableBot(groupId: String): Boolean {
        val me = cryptoManager.getPublicKeyString()
        return database.botDao().getAllBotsList().any { bot ->
            bot.isEnabled && isOwnedBy(bot.ownerPublicKey, me) &&
                getPermissions(bot).canReadMessages &&
                assignedGroupIds(bot).any { it.equals(groupId, ignoreCase = true) }
        }
    }

    /**
     * __BOT_NO_BOT_HINT__ (iOS swift:646-694) Tell the user, ONCE per group, that
     * a `/command` they sent has no bot to answer it — otherwise "there is no
     * bot" and "the bot is broken" look identical. Returns true when the hint
     * should be shown now, and records that it was.
     */
    suspend fun consumeNoBotHint(text: String, groupId: String): Boolean {
        if (!looksLikeACommand(text) || hasUsableBot(groupId)) return false
        val prefs = context.getSharedPreferences("oshi_bot_hints", Context.MODE_PRIVATE)
        val shown = prefs.getStringSet("no_bot_hint_groups", emptySet()).orEmpty()
        val key = groupId.lowercase()
        if (key in shown) return false
        prefs.edit().putStringSet("no_bot_hint_groups", (shown + key).toList().takeLast(500).toSet()).apply()
        return true
    }

    /**
     * The `/` menu entries for this group: every enabled bot this device owns
     * that is assigned here, one row per active `command` trigger. iOS feeds its
     * group slash bar from `botsForGroup(group.id)` (GroupViews.swift:4797-4800).
     */
    fun slashCommandsForGroup(groupId: String): Flow<List<com.oshi.messenger.util.SlashCommandCatalog.Entry>> =
        combine(bots, database.botDao().getAllTriggers()) { allBots, triggers ->
            val me = cryptoManager.getPublicKeyString()
            val here = allBots.filter { bot ->
                bot.isEnabled && isOwnedBy(bot.ownerPublicKey, me) &&
                    assignedGroupIds(bot).any { it.equals(groupId, ignoreCase = true) }
            }
            com.oshi.messenger.util.SlashCommandCatalog.botEntries(
                here.map { bot ->
                    com.oshi.messenger.util.SlashCommandCatalog.BotCommandSource(
                        botName = bot.name,
                        botEmoji = bot.avatarEmoji,
                        description = bot.description,
                        commandConditions = triggers
                            .filter { it.botId == bot.id && it.isActive && it.type == "command" }
                            .map { it.condition }
                    )
                }
            )
        }

    private fun assignedGroupIds(bot: OSHIBot): List<String> =
        bot.assignedGroups.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * The Detail screen's "Test" button. Returns whether the message left.
     * Goes through [emit] like every other bot output, so it now works for a
     * LOCAL bot — it used to POST to `/api/bot/send`, which refuses local bots.
     */
    suspend fun sendTestMessage(bot: OSHIBot, groupId: String, content: String): Boolean {
        if (groupId.isBlank()) return false
        return emit(bot.id, groupId, content)
    }

    /**
     * Substitute template variables in a trigger's response.
     *
     * iOS `generateResponse` (BotManager.swift:330-338) defines the canonical
     * set: `{{input}}`, `{{bot_name}}`, `{{date}}`, `{{timestamp}}`. Android used
     * `{{message}}`, `{{sender}}`, `{{member}}` — ZERO overlap, so a template
     * written on one platform rendered its own placeholders literally on the
     * other. The iOS names are now authoritative; the three Android names are
     * kept as aliases so templates already saved on Android keep working.
     */
    private fun renderTemplate(
        template: String,
        bot: OSHIBot,
        input: String,
        senderKey: String
    ): String = renderTemplate(template, bot.name, input, senderKey, System.currentTimeMillis())

    /**
     * THE one exit for a bot's words (iOS `emit`, swift:583-638,
     * __BOT_RUNTIME_OWNS_DELIVERY__ + __BOT_SCOPES_ENFORCED__): trigger,
     * schedule, webhook reply and Test all leave through here, so the scopes and
     * the rate limit have exactly one reader.
     *
     * __BOT_IN_THE_PIPELINE_2026_11_09__ Delivery is the NORMAL end-to-end
     * encrypted group send, from this (the owner's) device. It replaces a
     * cleartext POST to `/api/bot/send`, which the server refuses for a local
     * bot since __BOT_LOCAL_BOTS_LEAVE_NO_TRACE__ — so no Android bot but a
     * webhook one could ever speak.
     */
    private suspend fun emit(botId: String, groupId: String, content: String, hasMedia: Boolean = false): Boolean {
        val bot = database.botDao().getBotById(botId) ?: return false
        if (!bot.isEnabled) return false
        if (!isOwnedBy(bot.ownerPublicKey, cryptoManager.getPublicKeyString())) return false
        val perms = getPermissions(bot)
        val now = System.currentTimeMillis()
        val decision = synchronized(recentReplyTimes) {
            val recent = recentReplyTimes[botId].orEmpty().filter { it > now - 60_000L }
            val d = authorizeEmit(perms.canSendMessages, perms.canSendMedia, hasMedia, content, recent, now)
            // Only the window we still compare against; must not grow for the process lifetime.
            recentReplyTimes[botId] = if (d.allow) recent + now else recent
            d
        }
        if (!decision.allow) {
            Log.d(TAG, "🤖 emit refused (${decision.reason}) — bot ${bot.name}, group ${groupId.take(8)}")
            return false
        }
        val sent = groupManager.sendBotMessage(
            groupId = groupId,
            botSenderKey = botSenderKey(bot.id),
            botName = bot.name,
            content = botWireContent(bot.name, decision.content)
        )
        if (!sent) {
            Log.w(TAG, "🤖 emit: group ${groupId.take(8)} not found — bot ${bot.name}")
            return false
        }
        runCatching {
            database.botDao().insertBotMessage(
                BotMessage(botId = bot.id, groupId = groupId, content = decision.content, isFromBot = true)
            )
            database.botDao().updateBot(
                bot.copy(messagesSent = bot.messagesSent + 1, lastActivity = System.currentTimeMillis())
            )
        }
        return true
    }

    private suspend fun postAsBot(bot: OSHIBot, groupId: String, content: String, hasMedia: Boolean = false) {
        emit(bot.id, groupId, content, hasMedia)
    }

    // =========================================================================
    // MEDIA SENDING (with permission check)
    // =========================================================================

    suspend fun sendMediaAsBot(bot: OSHIBot, groupId: String, mediaContent: String) {
        val permissions = getPermissions(bot)
        if (!permissions.canSendMedia) {
            Log.d(TAG, "Bot ${bot.name} denied: canSendMedia=false")
            return
        }
        postAsBot(bot, groupId, mediaContent, hasMedia = true)
    }

    // =========================================================================
    // PERMISSIONS
    // =========================================================================

    private fun getPermissions(bot: OSHIBot): BotPermissions {
        return try {
            val json = JSONObject(bot.permissions)
            BotPermissions(
                canSendMessages = json.optBoolean("canSendMessages", true),
                canReadMessages = json.optBoolean("canReadMessages", true),
                canSendMedia = json.optBoolean("canSendMedia", false),
                canPinMessages = json.optBoolean("canPinMessages", false),
                canManageMembers = json.optBoolean("canManageMembers", false)
            )
        } catch (e: Exception) {
            BotPermissions() // defaults
        }
    }

    // =========================================================================
    // PIN MESSAGES (with permission check)  — task 1.6.4
    // =========================================================================

    suspend fun pinMessageAsBot(bot: OSHIBot, groupId: String, messageId: String) {
        val permissions = getPermissions(bot)
        if (!permissions.canPinMessages) {
            Log.d(TAG, "Bot ${bot.name} denied: canPinMessages=false")
            return
        }
        try {
            val body = JSONObject().apply {
                put("token", bot.token)
                put("groupId", groupId)
                put("messageId", messageId)
            }
            val reqBody = body.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${BuildConfig.VPS_URL}$BOT_API_BASE/pin")
                .post(reqBody)
                .addHeader("Content-Type", "application/json")
                .build()
            withContext(Dispatchers.IO) { OkHttpClient().newCall(req).execute() }
            Log.d(TAG, "Bot ${bot.name} pinned message $messageId in group $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Bot pin failed: ${e.message}")
        }
    }

    // =========================================================================
    // MANAGE MEMBERS (with permission check)  — task 1.6.5
    // =========================================================================

    suspend fun manageMemberAsBot(bot: OSHIBot, groupId: String, memberKey: String, action: String) {
        val permissions = getPermissions(bot)
        if (!permissions.canManageMembers) {
            Log.d(TAG, "Bot ${bot.name} denied: canManageMembers=false")
            return
        }
        try {
            val body = JSONObject().apply {
                put("token", bot.token)
                put("groupId", groupId)
                put("memberKey", memberKey)
                put("action", action) // "kick" or "invite"
            }
            val reqBody = body.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${BuildConfig.VPS_URL}$BOT_API_BASE/manage-member")
                .post(reqBody)
                .addHeader("Content-Type", "application/json")
                .build()
            withContext(Dispatchers.IO) { OkHttpClient().newCall(req).execute() }
            Log.d(TAG, "Bot ${bot.name} performed '$action' on $memberKey in group $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Bot manage member failed: ${e.message}")
        }
    }

    // =========================================================================
    // WEBHOOK
    // =========================================================================

    /** Validate webhook URL before saving — task 2.9.4 */
    fun isValidWebhookUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val u = java.net.URL(url)
            u.protocol in listOf("http", "https")
        } catch (_: Exception) {
            false
        }
    }

    /** Parse webhook JSON response: supports {"text":"..."}, {"message":"..."}, {"reply":"..."}, or plain text — task 2.9.2 */
    private fun parseWebhookResponse(rawBody: String): String? {
        if (rawBody.isBlank()) return null
        return try {
            val json = JSONObject(rawBody)
            json.optString("text").ifBlank { null }
                ?: json.optString("message").ifBlank { null }
                ?: json.optString("reply").ifBlank { null }
        } catch (_: Exception) {
            rawBody.trim().ifBlank { null }
        }
    }

    private suspend fun fireWebhook(
        bot: OSHIBot,
        triggerCondition: String,
        messageContent: String,
        senderKey: String,
        groupId: String
    ) {
        val webhookUrl = bot.webhookURL ?: return
        val body = JSONObject().apply {
            put("trigger", triggerCondition)
            put("message", messageContent)
            put("sender", senderKey)
            put("groupId", groupId)
        }
        val requestBody = body.toString().toRequestBody("application/json".toMediaType())

        // Retry up to 3 times with exponential backoff — task 2.9.3
        repeat(3) { attempt ->
            try {
                val request = Request.Builder()
                    .url(webhookUrl)
                    .post(requestBody)
                    .addHeader("Content-Type", "application/json")
                    .build()
                val response = withContext(Dispatchers.IO) {
                    OkHttpClient().newCall(request).execute()
                }
                val rawReply = response.body?.string() ?: ""
                val replyText = parseWebhookResponse(rawReply)
                if (replyText != null) {
                    // __BOT_IN_THE_PIPELINE_2026_11_09__ iOS hands a webhook's
                    // reply to `emit` (swift:846-850): it leaves this device over
                    // the encrypted group path, not the server's cleartext queue.
                    postAsBot(bot, groupId, replyText)
                }
                Log.d(TAG, "Webhook fired for bot ${bot.name}, attempt ${attempt + 1}, status=${response.code}")
                return // success
            } catch (e: Exception) {
                val backoffMs = (500L * (1 shl attempt))
                Log.w(TAG, "Webhook attempt ${attempt + 1} failed for ${bot.name}: ${e.message}, retrying in ${backoffMs}ms")
                if (attempt < 2) delay(backoffMs)
                else Log.e(TAG, "Webhook permanently failed for bot ${bot.name}")
            }
        }
    }

    // =========================================================================
    // SCHEDULED BOTS
    // =========================================================================

    private fun restartScheduledBot(bot: OSHIBot) {
        scheduledJobs[bot.id]?.cancel()
        if (!bot.isEnabled) return

        scheduledJobs[bot.id] = scope.launch {
            val triggers = database.botDao().getTriggersForBotList(bot.id)
            for (trigger in triggers) {
                if (trigger.type == "schedule" && trigger.isActive) {
                    launch {
                        // Unparseable (e.g. a cron string) → no timer, as on iOS.
                        val interval = scheduleIntervalMs(trigger.condition) ?: return@launch
                        Log.d(TAG, "Scheduled bot ${bot.name}: trigger '${trigger.condition}' -> interval ${interval}ms")
                        while (isActive) {
                            delay(interval)
                            val groups = bot.assignedGroups.split(",").filter { it.isNotEmpty() }
                            for (groupId in groups) {
                                // iOS renders the same template variables on the
                                // scheduled path with an empty input (swift:359).
                                postAsBot(bot, groupId, renderTemplate(trigger.responseTemplate, bot, "", ""))
                            }
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // SERVER REGISTRATION
    // =========================================================================

    /**
     * Does this bot have any business existing on the server at all?
     *
     * iOS `BotManager.needsServerRegistration` (`OSHI/BotManager.swift:872-874`), whose
     * discriminator is `manifest(for:).runtime == .remote` and whose only source is
     * `bot.type == .webhook` (`OSHI/BotManager.swift:338`).
     *
     * Registration uploads the GROUP NAME and the FULL ROSTER OF MEMBER PUBLIC KEYS,
     * in cleartext, to `/api/bot/register`, and it is what makes `/api/bot/send` accept
     * the token — a route that queues each message per member as base64 JSON, also in
     * cleartext, readable from `/api/pending/:key` with no credential. A LOCAL bot buys
     * nothing from any of it: its triggers run on this device and its replies go out
     * through the normal encrypted send path. So a purely local AI bot was putting its
     * group's roster — the user's contact graph — on the server, with no screen telling
     * them.
     */
    fun needsServerRegistration(bot: OSHIBot): Boolean = bot.type == "webhook"

    suspend fun registerBotWithServer(bot: OSHIBot): Boolean {
        if (!needsServerRegistration(bot)) {
            // Not merely "skip": a bot registered under an earlier build still has its
            // group name and member list sitting there. Withdraw it (iOS :878-884).
            Log.d(TAG, "🤖 local bot « ${bot.name} » — no server registration; withdrawing any existing trace")
            return runCatching { unregisterBotFromServer(bot) }.getOrDefault(false)
        }
        return try {
            val groups = bot.assignedGroups.split(",").filter { it.isNotEmpty() }
            val groupsArray = JSONArray()
            groups.forEach { groupId ->
                val group = database.groupDao().getGroupById(groupId)
                // `/api/bot/register` REPLACES the server-side group list, so a list we
                // know is incomplete must never become the truth. iOS aborts the whole
                // sync in that case (__BOT_PARTIAL_SYNC_2026_08_22__,
                // `OSHI/BotManager.swift:920-925`) after an amputated list cost it a
                // day of "Bot is not assigned to this group". Sending "Unknown" with an
                // empty roster is the same amputation with a name on it.
                if (group == null) {
                    Log.w(TAG, "🤖 ⛔️ sync of « ${bot.name} » CANCELLED: assigned group ${groupId.take(8)}… not found locally")
                    return false
                }
                groupsArray.put(JSONObject().apply {
                    put("id", groupId)
                    put("name", group.name)
                    put("members", JSONArray(group.memberPublicKeys))
                })
            }

            val body = JSONObject().apply {
                put("token", bot.token)
                put("botName", bot.name)
                put("ownerPublicKey", bot.ownerPublicKey)
                put("groups", groupsArray)
            }

            val reqBody = body.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${BuildConfig.VPS_URL}$BOT_API_BASE/register")
                .post(reqBody)
                .addHeader("Content-Type", "application/json")
                .build()
            val resp = withContext(Dispatchers.IO) {
                OkHttpClient().newCall(req).execute()
            }
            val response = if (resp.isSuccessful) {
                resp.body?.string()?.let { JSONObject(it) }
            } else null
            val success = response?.optBoolean("success", false) ?: false
            Log.d(TAG, "Bot registration: success=$success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register bot: ${e.message}")
            false
        }
    }

    suspend fun unregisterBotFromServer(bot: OSHIBot): Boolean {
        return try {
            val delReq = Request.Builder()
                .url("${BuildConfig.VPS_URL}$BOT_API_BASE/unregister?token=${bot.token}")
                .delete()
                .build()
            val delResp = withContext(Dispatchers.IO) {
                OkHttpClient().newCall(delReq).execute()
            }
            val response = if (delResp.isSuccessful) {
                delResp.body?.string()?.let { JSONObject(it) }
            } else null
            response?.optBoolean("success", false) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister bot: ${e.message}")
            false
        }
    }

    // =========================================================================
    // TOKEN GENERATION
    // =========================================================================

    private fun generateBotToken(): String {
        val random = ByteArray(32)
        SecureRandom().nextBytes(random)
        val digest = MessageDigest.getInstance("SHA-256").digest(random)
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun cleanup() {
        scheduledJobs.values.forEach { it.cancel() }
        scope.cancel()
    }
}
