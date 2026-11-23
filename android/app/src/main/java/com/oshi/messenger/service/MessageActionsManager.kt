package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.vps.VPSClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MessageActionsManager - Compatible with iOS MessageActionsManager.swift
 * Handles reply, forward, edit, and delete actions
 */
@Singleton
class MessageActionsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager,
    private val vpsClient: VPSClient
) {
    companion object {
        private const val TAG = "MessageActionsManager"

        // Android prefixes
        private const val REPLY_PREFIX = "\u21A9\uFE0FREPLY\u21A9\uFE0F"      // ↩️REPLY↩️
        private const val FORWARD_PREFIX = "\u27A1\uFE0FFORWARD\u27A1\uFE0F"  // ➡️FORWARD➡️
        private const val EDIT_PREFIX = "\u270F\uFE0FEDIT\u270F\uFE0F"        // ✏️EDIT✏️
        private const val DELETE_PREFIX = "\uD83D\uDDD1\uFE0FDELETE\uD83D\uDDD1\uFE0F"  // 🗑️DELETE🗑️

        // iOS prefixes (cross-platform compatibility)
        private const val REPLY_PREFIX_IOS = "\uD83D\uDCACREPLY\uD83D\uDCAC"      // 💬REPLY💬
        private const val FORWARD_PREFIX_IOS = "\u27A1\uFE0FFORWARDED\u27A1\uFE0F"  // ➡️FORWARDED➡️
        private const val ACTION_PREFIX_IOS = "\uD83D\uDD27ACTION\uD83D\uDD27"      // 🔧ACTION🔧 (iOS unified edit/delete/pin)

        /**
         * F-5. Forward detection and parsing, as pure functions on the companion,
         * so a bubble can draw the "Forwarded" badge without holding an injected
         * manager - `GroupChatScreen` has no instance, and neither screen should
         * need one to read a string. Both instance methods delegate here.
         *
         * iOS reads the forward info off the RAW text, before the envelope is
         * unwrapped for display (GroupViews.swift:2705-2716), so callers must pass
         * `message.content`, never the already-unwrapped display text.
         */
        fun isForwardEnvelope(content: String): Boolean =
            content.startsWith(FORWARD_PREFIX) || content.startsWith(FORWARD_PREFIX_IOS)

        /**
         * Android format: {"originalContent","originalSender","originalTimestamp","comment","mediaType"}
         * iOS format:     {"originalSenderName","content","isForwarded","forwardCount"}
         *                 (MessageActionsManager.swift:147-160)
         */
        fun parseForwardEnvelope(content: String): ForwardData? {
            val jsonStr = when {
                content.startsWith(FORWARD_PREFIX) -> content.removePrefix(FORWARD_PREFIX)
                content.startsWith(FORWARD_PREFIX_IOS) -> content.removePrefix(FORWARD_PREFIX_IOS)
                else -> return null
            }

            return try {
                val json = JSONObject(jsonStr)

                // iOS format (has "isForwarded" or "forwardCount")
                if (json.has("isForwarded") || json.has("forwardCount")) {
                    ForwardData(
                        originalContent = json.optString("content", ""),
                        originalSender = json.optString("originalSenderName", ""),
                        originalTimestamp = 0L, // iOS does not send a timestamp in a forward
                        comment = "",           // iOS has no separate comment field
                        mediaType = null,
                        forwardCount = json.optInt("forwardCount", 1)
                    )
                } else {
                    ForwardData(
                        originalContent = json.getString("originalContent"),
                        originalSender = json.getString("originalSender"),
                        originalTimestamp = json.getLong("originalTimestamp"),
                        comment = json.optString("comment", ""),
                        // 🔧 `MediaType.valueOf` here threw on any label this build does
                        // not know (iOS's incoming `gif`), and the catch below turned
                        // that into a null ForwardData — so the whole forwarded
                        // message, text included, vanished over ONE unreadable field.
                        // The type is decoration on a forward; the content is not.
                        mediaType = json.optString("mediaType", null)?.let { MediaType.fromWireLabel(it) },
                        forwardCount = 1
                    )
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Localised control-payload labels, resolved once from the application context.
     *
     * Needed by [createReplyContent] so a quoted location / call summary / check-in is
     * sanitised BEFORE it goes on the wire (RP-5). Lazy so a locale change at runtime is
     * picked up the next time the process starts, which is the same guarantee every other
     * `labels(context)` call site in the app has.
     */
    private val controlLabels: OSHIControlPayload.Labels by lazy {
        try {
            OSHIControlPayload.labels(context)
        } catch (_: Exception) {
            OSHIControlPayload.Labels()
        }
    }

    // State for currently selected message
    private val _selectedMessage = MutableStateFlow<Message?>(null)
    val selectedMessage: StateFlow<Message?> = _selectedMessage.asStateFlow()

    // State for reply-to message
    private val _replyToMessage = MutableStateFlow<Message?>(null)
    val replyToMessage: StateFlow<Message?> = _replyToMessage.asStateFlow()

    // Edited messages cache: originalId -> editedContent
    private val _editedMessages = MutableStateFlow<Map<String, String>>(emptyMap())
    val editedMessages: StateFlow<Map<String, String>> = _editedMessages.asStateFlow()

    // Deleted messages cache
    private val _deletedMessageIds = MutableStateFlow<Set<String>>(emptySet())
    val deletedMessageIds: StateFlow<Set<String>> = _deletedMessageIds.asStateFlow()

    // Callbacks
    private val editCallbacks = mutableListOf<(String, String) -> Unit>()
    private val deleteCallbacks = mutableListOf<(String) -> Unit>()
    // Pin callbacks: (targetMessageId, senderPublicKey?) -> Unit (null senderPublicKey = unpin)
    private val pinCallbacks = mutableListOf<(String, String?) -> Unit>()

    // =========================================================================
    // REPLY
    // =========================================================================

    /**
     * Set message to reply to
     */
    fun setReplyTo(message: Message?) {
        _replyToMessage.value = message
        Log.d(TAG, "Set reply to: ${message?.id}")
    }

    /**
     * Clear reply-to message
     */
    fun clearReplyTo() {
        _replyToMessage.value = null
    }

    /**
     * Create reply message content.
     *
     * Emits the iOS `💬REPLY💬` wire format (MessageWithReply) so an iPhone renders
     * the reply natively instead of showing raw JSON. Previously this emitted the
     * Android-only `↩️REPLY↩️` schema, which iOS does not parse (it hid neither the
     * marker nor the JSON), so replies sent from Android appeared as literal
     * `↩️REPLY↩️{…}` bubbles on iOS. Android's own parseReply()/isReply() already
     * accept the iOS format, so this stays fully readable on Android too.
     *
     * iOS MessageWithReply schema (MessageActionsManager.swift:15-77):
     *   {"content": <reply text>,
     *    "replyTo": {"originalMessageId","originalSenderKey","originalText",
     *                "originalTimestamp" (Apple-epoch seconds, Double, REQUIRED),
     *                "originalMediaType" (optional, omitted when absent)}}
     * originalTimestamp MUST be present and a Double — iOS decodes it as a non-optional
     * Swift Date (secondsSinceReferenceDate), and a missing/!Double field fails the
     * whole decode and drops the reply.
     */
    fun createReplyContent(originalMessage: Message, replyContent: String): String {
        // Unix millis -> Apple reference-date seconds (2001-01-01). 978307200 = the
        // offset iOS uses everywhere for cross-platform timestamps.
        val appleEpochSeconds = originalMessage.timestamp / 1000.0 - 978307200.0
        val replyTo = JSONObject().apply {
            put("originalMessageId", originalMessage.id)
            put("originalSenderKey", originalMessage.senderPublicKey)
            // RP-5 · the quoted preview must NOT be the raw wire body.
            //
            // This used to be `originalMessage.content.take(100)`, so replying to a
            // shared location / call summary / check-in put the sentinel JSON itself on
            // the wire as the quote text. iOS sanitises at CONSTRUCTION time so the JSON
            // never travels (`OSHI/MessageActionsManager.swift:29-33`) and again at render
            // (`:66`). Android's group path already truncates at iOS's 180
            // (`GroupManager.buildReplyEnvelope`); this brings the 1:1 path in line on both
            // counts — catalog first, then 180 rather than 100
            // (`OSHI/GroupViews.swift:4673`).
            put(
                "originalText",
                OSHIControlPayload.previewText(originalMessage.content, controlLabels).take(180)
            )
            put("originalTimestamp", appleEpochSeconds)
            // iOS originalMediaType is lowercase ("image","video","audio","document",
            // "location"); omit the key entirely for plain-text replies to match iOS,
            // which drops nil optionals rather than encoding null.
            originalMessage.mediaType?.let { put("originalMediaType", it.name.lowercase()) }
        }
        val json = JSONObject().apply {
            put("content", replyContent)
            put("replyTo", replyTo)
        }
        return "$REPLY_PREFIX_IOS${json}"
    }

    /**
     * Check if content is a reply (Android or iOS prefix)
     */
    fun isReply(content: String): Boolean =
        content.startsWith(REPLY_PREFIX) || content.startsWith(REPLY_PREFIX_IOS)

    /**
     * Parse reply content (handles both Android and iOS JSON formats)
     *
     * Android format: {"originalId","originalContent","originalSender","replyContent"}
     * iOS format: {"content","replyTo":{"originalMessageId","originalSenderKey","originalText","originalTimestamp","originalMediaType"}}
     */
    fun parseReply(content: String): ReplyData? {
        val jsonStr = when {
            content.startsWith(REPLY_PREFIX) -> content.removePrefix(REPLY_PREFIX)
            content.startsWith(REPLY_PREFIX_IOS) -> content.removePrefix(REPLY_PREFIX_IOS)
            else -> return null
        }

        return try {
            val json = JSONObject(jsonStr)

            // Check for iOS nested format first
            if (json.has("replyTo")) {
                val replyTo = json.getJSONObject("replyTo")
                ReplyData(
                    originalId = replyTo.optString("originalMessageId", ""),
                    originalContent = replyTo.optString("originalText", ""),
                    originalSender = replyTo.optString("originalSenderKey", ""),
                    replyContent = json.optString("content", "")
                )
            } else {
                // Android format
                ReplyData(
                    originalId = json.getString("originalId"),
                    originalContent = json.getString("originalContent"),
                    originalSender = json.getString("originalSender"),
                    replyContent = json.getString("replyContent")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse reply: ${e.message}")
            null
        }
    }

    // =========================================================================
    // FORWARD
    // =========================================================================

    // TOMBSTONE `forwardMessage(originalMessage, recipientPublicKey, additionalComment)`
    // DELETED (F-18, 2026-08-23).
    //
    // Zero callers, and wrong in four ways had anyone wired it:
    //   * Android-only prefix (the wire prefix iOS reads is FORWARD_PREFIX_IOS,
    //     which this emitted as the Android FORWARD_PREFIX);
    //   * Android-only schema (`originalContent`/`originalSender`) where iOS's
    //     `ForwardedMessage` is `originalSenderName`/`content`/`isForwarded`/
    //     `forwardCount` (MessageActionsManager.swift:147-160);
    //   * Unix-millis `originalTimestamp` where a Swift `Date` under a default
    //     JSONEncoder is Apple-epoch seconds;
    //   * it sent through the STRING overload of `vpsClient.sendMessage`, i.e. in
    //     PLAINTEXT, while every sibling control payload encrypts first.
    // The live path is `ChatViewModel.forwardMessage` (:362-395), which builds
    // exactly iOS's shape. [parseForwardEnvelope] below still reads BOTH shapes,
    // because messages sent by older Android builds are on devices today.

    /**
     * Check if content is a forwarded message (Android or iOS prefix)
     */
    fun isForwarded(content: String): Boolean = isForwardEnvelope(content)

    /**
     * Parse forwarded content (handles both Android and iOS JSON formats)
     */
    fun parseForward(content: String): ForwardData? = parseForwardEnvelope(content)

    // =========================================================================
    // EDIT
    // =========================================================================

    // ⚰️ `editMessage(originalMessage, newContent, recipientPublicKey)` DELETED
    // (2026-08-13 cross-file integration, item H).
    //
    // It had zero callers — `ChatViewModel.editMessage` (`:399`) goes to
    // `MessageRepository.editMessage` (`:3998`) — and it was actively wrong in two
    // ways that would have broken the feature had anyone wired it:
    //   • it sent the payload through the STRING overload of `vpsClient.sendMessage`,
    //     i.e. in PLAINTEXT, while the repository's emitter encrypts;
    //   • it stamped `timestamp` as Unix MILLIS, but a Swift `Date` in a default
    //     `JSONEncoder` is seconds since the 2001-01-01 Apple epoch
    //     (`OSHI/MessageManager.swift:8098,8110-8114`), so the iPhone decoded a date
    //     ~55 000 years in the future.
    // The live emitter is `MessageRepository.editMessage` + `buildActionPayload`
    // (`MessageRepository.kt:145-165`), which is unit-tested by
    // `MessageActionWireFormatTest`.

    /**
     * Check if content is an edit message (Android ✏️EDIT✏️ or iOS 🔧ACTION🔧 with editMessage)
     */
    fun isEdit(content: String): Boolean {
        if (content.startsWith(EDIT_PREFIX)) return true
        if (content.startsWith(ACTION_PREFIX_IOS)) {
            return try {
                val json = JSONObject(content.removePrefix(ACTION_PREFIX_IOS))
                json.optString("actionType") == "editMessage"
            } catch (e: Exception) { false }
        }
        return false
    }

    /**
     * Check if content is a delete message (Android 🗑️DELETE🗑️ or iOS 🔧ACTION🔧 with deleteForEveryone)
     */
    fun isDelete(content: String): Boolean {
        if (content.startsWith(DELETE_PREFIX)) return true
        if (content.startsWith(ACTION_PREFIX_IOS)) {
            return try {
                val json = JSONObject(content.removePrefix(ACTION_PREFIX_IOS))
                json.optString("actionType") == "deleteForEveryone"
            } catch (e: Exception) { false }
        }
        return false
    }

    /**
     * Check if content is an iOS action message (edit, delete, pin, unpin)
     */
    fun isActionMessage(content: String): Boolean = content.startsWith(ACTION_PREFIX_IOS)

    /**
     * Handle incoming iOS unified action message (🔧ACTION🔧)
     * Routes to edit or delete based on actionType field
     */
    fun handleIncomingAction(content: String, senderPublicKey: String) {
        if (!content.startsWith(ACTION_PREFIX_IOS)) return

        try {
            val jsonStr = content.removePrefix(ACTION_PREFIX_IOS)
            val json = JSONObject(jsonStr)
            val actionType = json.optString("actionType", "")
            val targetMessageId = json.optString("targetMessageId", "")

            when (actionType) {
                "editMessage" -> {
                    val newContent = json.optString("newContent", "")
                    if (targetMessageId.isNotEmpty() && newContent.isNotEmpty()) {
                        val current = _editedMessages.value.toMutableMap()
                        current[targetMessageId] = newContent
                        _editedMessages.value = current
                        editCallbacks.forEach { it(targetMessageId, newContent) }
                        Log.d(TAG, "Received iOS edit for message $targetMessageId")
                    }
                }
                "deleteForEveryone" -> {
                    if (targetMessageId.isNotEmpty()) {
                        val current = _deletedMessageIds.value.toMutableSet()
                        current.add(targetMessageId)
                        _deletedMessageIds.value = current
                        deleteCallbacks.forEach { it(targetMessageId) }
                        Log.d(TAG, "Received iOS delete for message $targetMessageId")
                    }
                }
                "pinMessage" -> {
                    if (targetMessageId.isNotEmpty()) {
                        pinCallbacks.forEach { it(targetMessageId, senderPublicKey) }
                        Log.d(TAG, "Received pin for message $targetMessageId")
                    }
                }
                "unpinMessage" -> {
                    if (targetMessageId.isNotEmpty()) {
                        pinCallbacks.forEach { it(targetMessageId, null) }
                        Log.d(TAG, "Received unpin for message $targetMessageId")
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown iOS action type: $actionType")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse iOS action: ${e.message}")
        }
    }

    /**
     * Handle incoming edit notification (Android ✏️EDIT✏️ or iOS 🔧ACTION🔧)
     */
    fun handleIncomingEdit(content: String, senderPublicKey: String) {
        // Handle iOS unified action format
        if (content.startsWith(ACTION_PREFIX_IOS)) {
            handleIncomingAction(content, senderPublicKey)
            return
        }

        if (!content.startsWith(EDIT_PREFIX)) return

        try {
            val jsonStr = content.removePrefix(EDIT_PREFIX)
            val json = JSONObject(jsonStr)

            // Android format: "messageId", iOS action format: "targetMessageId"
            val messageId = json.optString("messageId", "") .ifEmpty { json.optString("targetMessageId", "") }
            val newContent = json.getString("newContent")

            // Update cache
            val current = _editedMessages.value.toMutableMap()
            current[messageId] = newContent
            _editedMessages.value = current

            // Notify callbacks
            editCallbacks.forEach { it(messageId, newContent) }

            Log.d(TAG, "Received edit for message $messageId")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse edit: ${e.message}")
        }
    }

    /**
     * Get edited content for a message
     */
    fun getEditedContent(messageId: String): String? = _editedMessages.value[messageId]

    /**
     * Check if message was edited
     */
    fun isEdited(messageId: String): Boolean = _editedMessages.value.containsKey(messageId)

    // =========================================================================
    // DELETE
    // =========================================================================

    // ⚰️ `deleteMessageForEveryone(message, recipientPublicKey)` DELETED
    // (2026-08-13 cross-file integration, item H). Same three defects as the deleted
    // `editMessage` above — zero callers, PLAINTEXT via the String overload of
    // `vpsClient.sendMessage`, Unix millis where iOS decodes an Apple-epoch Double —
    // plus a fourth: it put `JSONObject.NULL` in `newContent`, while Swift's
    // synthesized Codable uses `encodeIfPresent`, so iOS expects the key to be ABSENT
    // (`MessageRepository.buildActionPayload` gets this right).
    // Live emitter: `MessageRepository.deleteMessageForEveryone` (`:3969`).

    /**
     * Delete message locally only
     */
    fun deleteMessageLocally(messageId: String) {
        val current = _deletedMessageIds.value.toMutableSet()
        current.add(messageId)
        _deletedMessageIds.value = current

        deleteCallbacks.forEach { it(messageId) }
    }

    /**
     * Handle incoming delete notification (Android 🗑️DELETE🗑️ or iOS 🔧ACTION🔧)
     */
    fun handleIncomingDelete(content: String, senderPublicKey: String) {
        // Handle iOS unified action format
        if (content.startsWith(ACTION_PREFIX_IOS)) {
            handleIncomingAction(content, senderPublicKey)
            return
        }

        if (!content.startsWith(DELETE_PREFIX)) return

        try {
            val jsonStr = content.removePrefix(DELETE_PREFIX)
            val json = JSONObject(jsonStr)

            // Android format: "messageId", iOS action format: "targetMessageId"
            val messageId = json.optString("messageId", "").ifEmpty { json.optString("targetMessageId", "") }

            // Update cache
            val current = _deletedMessageIds.value.toMutableSet()
            current.add(messageId)
            _deletedMessageIds.value = current

            // Notify callbacks
            deleteCallbacks.forEach { it(messageId) }

            Log.d(TAG, "Received delete for message $messageId")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse delete: ${e.message}")
        }
    }

    /**
     * Check if message was deleted
     */
    fun isDeleted(messageId: String): Boolean = _deletedMessageIds.value.contains(messageId)

    // ⚰️ `pinMessageAndSync` / `unpinMessageAndSync` DELETED
    // (2026-08-13 cross-file integration, item H).
    //
    // Zero callers, and they could not have worked: same PLAINTEXT String overload and
    // same Unix-millis timestamp as the two emitters above. They are also the wrong
    // CHANNEL — a 1:1 pin is not something iOS listens for on `🔧ACTION🔧`; the pin
    // that iOS actually acts on rides inside the `MessageGroup` payload as
    // `pinnedMessageId`/`pinnedBy` (`OSHI/GroupMessaging.swift:104`, applied at
    // `:840-846`), which `GroupManager.buildFullGroupUpdateContent` now sends and
    // `applyiOSGroupUpdateBroadcast` now ingests in both directions. Deleted rather
    // than wired to a UI action, because wiring them would put a frame on the wire
    // that no iPhone reads.

    // =========================================================================
    // SELECTION
    // =========================================================================

    /**
     * Select a message for actions
     */
    fun selectMessage(message: Message?) {
        _selectedMessage.value = message
    }

    /**
     * Clear selection
     */
    fun clearSelection() {
        _selectedMessage.value = null
    }

    // =========================================================================
    // CALLBACKS
    // =========================================================================

    fun addEditCallback(callback: (String, String) -> Unit) {
        editCallbacks.add(callback)
    }

    fun removeEditCallback(callback: (String, String) -> Unit) {
        editCallbacks.remove(callback)
    }

    fun addDeleteCallback(callback: (String) -> Unit) {
        deleteCallbacks.add(callback)
    }

    fun removeDeleteCallback(callback: (String) -> Unit) {
        deleteCallbacks.remove(callback)
    }

    fun addPinCallback(callback: (String, String?) -> Unit) {
        pinCallbacks.add(callback)
    }

    fun removePinCallback(callback: (String, String?) -> Unit) {
        pinCallbacks.remove(callback)
    }

    fun cleanup() {
        scope.cancel()
        editCallbacks.clear()
        deleteCallbacks.clear()
        pinCallbacks.clear()
    }
}

/**
 * Data class for reply information
 */
data class ReplyData(
    val originalId: String,
    val originalContent: String,
    val originalSender: String,
    val replyContent: String
)

/**
 * Data class for forwarded message information
 */
data class ForwardData(
    val originalContent: String,
    val originalSender: String,
    val originalTimestamp: Long,
    val comment: String,
    val mediaType: MediaType?,
    /** iOS `ForwardedMessage.forwardCount` (MessageActionsManager.swift:147-160). */
    val forwardCount: Int = 1
)
