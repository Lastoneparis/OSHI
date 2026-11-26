package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import com.oshi.messenger.data.local.AppDatabase
import com.oshi.messenger.data.model.MessageReaction
import com.oshi.messenger.data.model.ReactionEmojis
import com.oshi.messenger.data.model.ReactionSummary
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.vps.VPSClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MessageReactionManager - Compatible with iOS MessageReactionManager.swift
 * Handles emoji reactions on messages
 */
@Singleton
class MessageReactionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager,
    private val vpsClient: VPSClient,
    private val database: AppDatabase
) {
    companion object {
        private const val TAG = "MessageReactionManager"
        // 🔧 CROSS-PLATFORM: Support both Android and iOS reaction prefixes
        private const val REACTION_PREFIX = "\uD83D\uDC4D REACTION\uD83D\uDC4D"      // 👍REACTION👍 (Android)
        private const val REACTION_PREFIX_IOS = "\uD83D\uDD25REACTION\uD83D\uDD25"  // 🔥REACTION🔥 (iOS)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val reactionDao = database.reactionDao()

    /**
     * 📡 Transport fan-out, injected by `MessageRepository` at init (the same
     * avoid-circular-DI pattern `GroupManager.contactAliasResolver` uses).
     *
     * R-2 of the 2026-08-24 react-reply audit: this class sent reactions straight down
     * `vpsClient`, so a reaction to a peer reachable only over Nearby / BLE vanished with
     * no error — while the message being reacted to had arrived over that very mesh link.
     * iOS routes every reaction through the normal send path, which fans out to mesh AND
     * VPS (`OSHI/ChatView.swift:4525-4530`).
     *
     * Receives the payload **already encrypted**, so the wire bytes stay exactly what the
     * iOS strict decoder accepts today. Null (nobody registered) falls back to VPS-only,
     * which is the old behaviour.
     */
    var transportSender: (
        suspend (
            recipientPublicKey: String,
            encrypted: ByteArray,
            /**
             * __CONTROL_PAYLOADS_NEED_THE_V2_LANE_2026_10_29__ The same payload BEFORE
             * encryption, so the fan-out's relay leg can offer it to V2 — which encrypts for
             * itself and therefore cannot use the bytes above.
             *
             * Without this a reaction reached the relay only through `sendMessageViaIPFS`,
             * so a peer that speaks V2 and not the legacy lane never received one. The
             * encrypted form is still passed because the MESH legs send exactly those bytes.
             */
            plaintext: String,
        ) -> Unit
    )? = null

    // State: messageId -> list of reactions
    private val _reactions = MutableStateFlow<Map<String, List<MessageReaction>>>(emptyMap())
    val reactions: StateFlow<Map<String, List<MessageReaction>>> = _reactions.asStateFlow()

    init {
        // Load persisted reactions from database on startup
        scope.launch {
            try {
                val allReactions = reactionDao.getAllReactionsList()
                val grouped = allReactions.groupBy { it.messageId }
                _reactions.value = grouped
                Log.d(TAG, "Loaded ${allReactions.size} reactions from database")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load reactions: ${e.message}")
            }
        }
    }

    // Callbacks for UI updates.
    //
    // ⚠️ Z-4 / R-6 of the 2026-08-24 react-reply audit: `addReactionCallback` has ZERO
    // registrations app-wide. This is NOT the live update path — the UI observes the
    // `reactions` StateFlow directly (`ChatScreen.kt` collects `reactionManager.reactions`).
    // Kept as a public extension point; if you are debugging "my reaction handler never
    // fires", it is because nobody ever called `addReactionCallback`.
    /**
     * __REACTION_WAS_SILENT_2026_11_03__ Called once per inbound ADDED
     * reaction, with the id of the message reacted to, the emoji, and who sent
     * it. The repository decides whether it is worth a notification — only a
     * reaction to one of OUR messages is — because only it can look the
     * message up.
     */
    var onIncomingReaction: ((messageId: String, emoji: String, senderPublicKey: String) -> Unit)? = null

    private val reactionCallbacks = mutableListOf<(String, ReactionSummary) -> Unit>()

    /**
     * Add reaction to a message
     */
    fun addReaction(
        messageId: String,
        emoji: String,
        recipientPublicKey: String
    ) {
        val myPublicKey = cryptoManager.getPublicKeyString()

        // Check if already reacted with same emoji
        val currentReactions = _reactions.value[messageId] ?: emptyList()
        val mine = currentReactions.find { it.senderPublicKey == myPublicKey && it.emoji == emoji }
            ?: currentReactions.find { it.senderPublicKey == myPublicKey }

        // __OSHI_SMILE_REACTION_2026_11_09__ the rule, in one pure place.
        if (com.oshi.messenger.data.model.ReactionToggle.next(mine?.emoji, emoji) == null) {
            // Remove existing reaction (toggle)
            removeReaction(messageId, emoji, recipientPublicKey)
            return
        }

        // Remove any existing reaction from me (only one reaction per user)
        val myExisting = currentReactions.find { it.senderPublicKey == myPublicKey }
        if (myExisting != null) {
            removeReactionLocally(messageId, myPublicKey)
        }

        // Add new reaction
        val reaction = MessageReaction(
            messageId = messageId,
            senderPublicKey = myPublicKey,
            emoji = emoji,
            timestamp = System.currentTimeMillis()
        )

        addReactionLocally(reaction)

        // Send to recipient
        scope.launch {
            sendReactionNotification(messageId, emoji, recipientPublicKey, isAdding = true)
        }

        Log.d(TAG, "Added reaction $emoji to message $messageId")
    }

    /**
     * Remove reaction from a message
     */
    fun removeReaction(
        messageId: String,
        emoji: String,
        recipientPublicKey: String
    ) {
        val myPublicKey = cryptoManager.getPublicKeyString()
        removeReactionLocally(messageId, myPublicKey)

        // Notify recipient
        scope.launch {
            sendReactionNotification(messageId, emoji, recipientPublicKey, isAdding = false)
        }

        Log.d(TAG, "Removed reaction $emoji from message $messageId")
    }

    /**
     * Get reaction summary for a message
     */
    fun getReactionSummary(messageId: String): ReactionSummary {
        val messageReactions = _reactions.value[messageId] ?: emptyList()
        val myPublicKey = cryptoManager.getPublicKeyString()

        val reactionMap = mutableMapOf<String, MutableList<String>>()
        var myReaction: String? = null

        messageReactions.forEach { reaction ->
            val list = reactionMap.getOrPut(reaction.emoji) { mutableListOf() }
            list.add(reaction.senderPublicKey)
            if (reaction.senderPublicKey == myPublicKey) {
                myReaction = reaction.emoji
            }
        }

        return ReactionSummary(
            messageId = messageId,
            reactions = reactionMap,
            myReaction = myReaction
        )
    }

    /**
     * Handle incoming reaction notification
     * 🔧 CROSS-PLATFORM: Supports both iOS and Android reaction formats
     *
     * Android format: 👍REACTION👍{"messageId":"...", "emoji":"...", "isAdding": true}
     * iOS format: 🔥REACTION🔥{"messageId":"...", "emoji":"👍", "senderPublicKey":"...",
     *             "senderName":"...", "timestamp":774123456.789, "action":"add"|"remove"}
     *
     * ⚠️ `emoji` is a **bare string** on the iOS wire. `ReactionEmoji`
     * (`OSHI/MessageReactionManager.swift:20-49`) is a `String`-raw-value enum, so a default
     * `JSONEncoder` emits `"emoji":"👍"` — NOT `{"rawValue":"👍"}`, which this comment used to
     * claim. Do not "fix" the emitter to match the old comment: iOS's lenient decoder tier
     * reads `json["emoji"] as? String` (`swift:158`) and an object there is the ONE shape it
     * cannot recover, so the whole payload would return nil.
     * The object form is still tolerated below, defensively, for older Android builds.
     *
     * `timestamp` is Apple-epoch seconds (a JSON **number**), not ISO-8601.
     */
    fun handleIncomingReaction(content: String, senderPublicKey: String) {
        val isAndroidFormat = content.startsWith(REACTION_PREFIX)
        val isIOSFormat = content.startsWith(REACTION_PREFIX_IOS)

        if (!isAndroidFormat && !isIOSFormat) return

        try {
            val jsonStr = if (isAndroidFormat) {
                content.removePrefix(REACTION_PREFIX)
            } else {
                content.removePrefix(REACTION_PREFIX_IOS)
            }
            val json = JSONObject(jsonStr)

            val messageId = json.getString("messageId")

            // Parse emoji — iOS may send {rawValue: "👍"} or just "👍"
            val emoji = if (json.opt("emoji") is JSONObject) {
                json.getJSONObject("emoji").optString("rawValue", "👍")
            } else {
                json.getString("emoji")
            }

            // Parse add/remove — Android uses "isAdding" boolean, iOS uses "action" string
            val isAdding = if (json.has("isAdding")) {
                json.getBoolean("isAdding")
            } else {
                json.optString("action", "add") == "add"
            }

            if (isAdding) {
                val reaction = MessageReaction(
                    messageId = messageId,
                    senderPublicKey = senderPublicKey,
                    emoji = emoji,
                    timestamp = System.currentTimeMillis()
                )
                addReactionLocally(reaction)
            } else {
                removeReactionLocally(messageId, senderPublicKey)
            }

            // Notify callbacks
            val summary = getReactionSummary(messageId)
            reactionCallbacks.forEach { it(messageId, summary) }

            // __REACTION_WAS_SILENT_2026_11_03__ (Anatole) "réagir à un message
            // avec un émoji ne déclenche pas de notification."
            //
            // True on Android and not on iOS, which has rendered
            // "🔥 Reacted to a message" for its notifications all along
            // (NotificationManager.swift:752). Here the inbound reaction was
            // handled and the function returned, so it never reached the
            // notification path at all.
            //
            // ONE hook, called from the single place every inbound reaction
            // passes through, rather than a check at each of the four inbound
            // funnels — the omission-on-one-path shape this codebase has now
            // produced twice.
            if (isAdding) {
                runCatching { onIncomingReaction?.invoke(messageId, emoji, senderPublicKey) }
                    .onFailure { Log.w(TAG, "reaction notification hook: ${it.message}") }
            }

            Log.d(TAG, "Received reaction ${if (isAdding) "add" else "remove"}: $emoji on $messageId (${if (isIOSFormat) "iOS" else "Android"} format)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse reaction: ${e.message}")
        }
    }

    /**
     * Check if content is a reaction message (from either platform)
     */
    fun isReactionMessage(content: String): Boolean {
        return content.startsWith(REACTION_PREFIX) || content.startsWith(REACTION_PREFIX_IOS)
    }

    /**
     * Add callback for reaction updates
     */
    fun addReactionCallback(callback: (String, ReactionSummary) -> Unit) {
        reactionCallbacks.add(callback)
    }

    /**
     * Remove callback
     */
    fun removeReactionCallback(callback: (String, ReactionSummary) -> Unit) {
        reactionCallbacks.remove(callback)
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    private fun addReactionLocally(reaction: MessageReaction) {
        val currentReactions = _reactions.value.toMutableMap()
        val messageReactions = currentReactions[reaction.messageId]?.toMutableList() ?: mutableListOf()

        // Remove existing from same sender
        messageReactions.removeAll { it.senderPublicKey == reaction.senderPublicKey }
        messageReactions.add(reaction)

        currentReactions[reaction.messageId] = messageReactions
        _reactions.value = currentReactions

        // Persist to database
        scope.launch {
            try {
                reactionDao.removeReaction(reaction.messageId, reaction.senderPublicKey)
                reactionDao.insert(reaction)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist reaction: ${e.message}")
            }
        }
    }

    private fun removeReactionLocally(messageId: String, senderPublicKey: String) {
        val currentReactions = _reactions.value.toMutableMap()
        val messageReactions = currentReactions[messageId]?.toMutableList() ?: return

        messageReactions.removeAll { it.senderPublicKey == senderPublicKey }

        if (messageReactions.isEmpty()) {
            currentReactions.remove(messageId)
        } else {
            currentReactions[messageId] = messageReactions
        }

        _reactions.value = currentReactions

        // Persist removal to database
        scope.launch {
            try {
                reactionDao.removeReaction(messageId, senderPublicKey)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove reaction from db: ${e.message}")
            }
        }
    }

    /**
     * Send reaction in iOS-compatible format so it decodes correctly on both platforms.
     *
     * iOS Codable format: 🔥REACTION🔥{"messageId":"...", "emoji":"👍", "senderPublicKey":"...",
     *   "senderName":"...", "timestamp":726537600.0, "action":"add"|"remove"}
     *
     * timestamp: seconds since Apple reference date (Jan 1, 2001 00:00:00 UTC)
     */
    private suspend fun sendReactionNotification(
        messageId: String,
        emoji: String,
        recipientPublicKey: String,
        isAdding: Boolean
    ) {
        // __MESHCORE_INTEROP_2026_11_25__ A MeshCore user's app cannot read an OSHI reaction: it stays local.
        if (com.oshi.messenger.service.lora.MeshCoreThread.isMeshCoreKey(recipientPublicKey)) return
        // Apple reference date offset: seconds between Unix epoch and Jan 1, 2001
        val appleEpochOffset = 978307200L
        val timestampApple = (System.currentTimeMillis() / 1000.0) - appleEpochOffset

        val json = JSONObject().apply {
            put("messageId", messageId)
            put("emoji", emoji)
            put("senderPublicKey", cryptoManager.getPublicKeyString())
            put("senderName", "")  // iOS falls back to "Someone" when empty
            put("timestamp", timestampApple)
            put("action", if (isAdding) "add" else "remove")
        }

        // Use iOS prefix so both platforms decode it natively
        val content = "$REACTION_PREFIX_IOS${json}"

        try {
            // Encrypt reaction before sending (same as regular messages)
            val encrypted = cryptoManager.encryptMessage(content, recipientPublicKey)
            // Mesh + VPS when the repository has registered the fan-out; VPS alone otherwise.
            val sender = transportSender
            if (sender != null) sender(recipientPublicKey, encrypted, content)
            else vpsClient.sendMessage(recipientPublicKey, encrypted)
            Log.d(TAG, "Sent reaction (iOS format): $emoji for $messageId, action=${if (isAdding) "add" else "remove"}, mesh=${sender != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reaction notification: ${e.message}")
        }
    }

    fun cleanup() {
        scope.cancel()
        reactionCallbacks.clear()
    }
}
