package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.vps.VPSClient
import com.oshi.messenger.network.mesh.MeshNetworkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.oshi.messenger.ui.viewmodel.dataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TypingIndicatorManager - Compatible with iOS TypingIndicatorManager.swift
 * Handles typing status indicators (1-on-1 and groups)
 */
@Singleton
class TypingIndicatorManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager,
    private val vpsClient: VPSClient,
    /**
     * __CONTROL_OVER_V2_2026_10_28__ typing went out on the LEGACY lane only, so a peer
     * that speaks V2 and not the legacy path — the desktop client, deliberately (its
     * PARITY row 0.23: "ipfs was the old road") — never saw an Android user typing, while
     * the message that followed arrived fine.
     */
    private val v2MessageRouter: com.oshi.messenger.network.v2.V2MessageRouter
) {
    companion object {
        private const val TAG = "TypingIndicatorManager"
        // Spellings come from the ONE catalog (OSHIControlPayload.kt), not from a
        // local copy — a fifth private prefix list is exactly what that file
        // exists to prevent.
        private const val TYPING_PREFIX = OSHIControlPayload.TYPING_ANDROID   // ✍️TYPING✍️ (Android)
        private const val TYPING_PREFIX_IOS = OSHIControlPayload.TYPING_IOS   // ⌨️TYPING⌨️ (iOS)
        private const val TYPING_TIMEOUT_MS = 5000L  // 5 seconds
        private const val SEND_INTERVAL_MS = 3000L   // Send every 3 seconds while typing

        /** Seconds between the Unix epoch (1970-01-01) and Apple's reference date (2001-01-01). */
        private const val APPLE_EPOCH_OFFSET = 978307200.0

        /**
         * `Date` in a Swift `Codable` struct encodes, under the DEFAULT
         * `JSONEncoder`/`JSONDecoder` strategy (`.deferredToDate`), as a Double
         * count of seconds since 2001-01-01 — not Unix seconds, not millis.
         * `TypingStatus` (TypingIndicatorManager.swift:14-42) uses a plain
         * `JSONDecoder()` (swift:57), so Unix millis here decodes to the year
         * ~55000 and, worse, the wrong TYPE never even gets that far.
         */
        fun nowAsAppleTimestamp(): Double =
            (System.currentTimeMillis() / 1000.0) - APPLE_EPOCH_OFFSET

        /**
         * The exact body iOS's `TypingStatus` decoder requires.
         *
         * `TypingIndicatorManager.swift:36-42` decodes `senderPublicKey`, `isTyping`
         * and `timestamp` with `try c.decode(...)` — **non-optional**. Any one of
         * them missing throws, `JSONDecoder` returns nil at swift:57, and
         * `fromMessageString` yields nil: the ping is dropped in silence.
         *
         * Android used to send `{"isTyping":true}` only, so the 1.5.6 fix that
         * corrected the ⌨️ prefix still never made an iPhone show
         * "… is typing"; it only changed the failure from "renders raw" to
         * "silently discarded" (the prefix is in iOS's silent catalog).
         *
         * `groupId` and `senderName` are `decodeIfPresent` on iOS, so they stay
         * optional here — `groupId` is omitted entirely for a 1-on-1 ping,
         * matching iOS's `var groupId: String? = nil` default.
         */
        fun buildTypingPayload(
            senderPublicKey: String,
            isTyping: Boolean,
            appleTimestamp: Double,
            groupId: String? = null,
            senderName: String? = null
        ): JSONObject = JSONObject().apply {
            put("senderPublicKey", senderPublicKey)
            put("isTyping", isTyping)
            put("timestamp", appleTimestamp)
            if (groupId != null) put("groupId", groupId)
            if (senderName != null) put("senderName", senderName)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State: publicKey -> isTyping (1-on-1)
    private val _typingStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val typingStatus: StateFlow<Map<String, Boolean>> = _typingStatus.asStateFlow()

    // Group typing state: groupId -> set of senderPublicKeys currently typing
    private val _groupTypingStatus = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val groupTypingStatus: StateFlow<Map<String, Set<String>>> = _groupTypingStatus.asStateFlow()

    // Timeout jobs for each peer (1-on-1 key = publicKey; group key = "groupId|publicKey")
    private val typingTimeouts = mutableMapOf<String, Job>()

    // Last send time to throttle outgoing typing indicators
    private var lastSendTime = 0L
    private var currentRecipient: String? = null

    // (the group send-throttle map went with the deleted 1:1 fan-out —
    //  GroupManager.sendGroupTypingStatus owns that debounce now)

    // Callbacks
    private val typingCallbacks = mutableListOf<(String, Boolean) -> Unit>()

    /**
     * Called when user starts typing
     */
    fun userStartedTyping(recipientPublicKey: String) {
        currentRecipient = recipientPublicKey
        sendTypingIndicator(recipientPublicKey, isTyping = true)
    }

    /**
     * Called when user stops typing (sent message or cleared input)
     */
    fun userStoppedTyping(recipientPublicKey: String) {
        currentRecipient = null
        sendTypingIndicator(recipientPublicKey, isTyping = false)
    }

    /**
     * Called on each keystroke to throttle typing indicators
     */
    fun onTextChanged(recipientPublicKey: String, text: String) {
        if (text.isEmpty()) {
            userStoppedTyping(recipientPublicKey)
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastSendTime >= SEND_INTERVAL_MS) {
            userStartedTyping(recipientPublicKey)
            lastSendTime = now
        }
    }

    // =========================================================================
    // GROUP TYPING
    // =========================================================================

    // ⚰️ The OUTBOUND group-typing fan-out that lived here —
    // `userStartedTyping(groupId, members)`, `userStoppedTyping(groupId, members)` and
    // `onTextChanged(groupId, members, text)` — is DELETED (2026-08-13 cross-file
    // integration, item H).
    //
    // It sent one SEPARATE 1:1 `⌨️TYPING⌨️` message to every member. No iPhone can
    // receive that: iOS has no live 1:1 typing receiver at all, and its only group
    // ingress is `receiveGroupMessage` sniffing the prefix off a decoded GROUP
    // envelope body (`OSHI/GroupMessaging.swift:2905-2912` →
    // `TypingIndicatorManager.receiveGroupTypingStatus`). So an N-member group cost N
    // encrypted 1:1 sends per keystroke window and produced exactly zero "… is typing"
    // on the other side.
    //
    // The real path is `GroupManager.sendGroupTypingStatus` (`GroupManager.kt:1383`),
    // which wraps the ping in the group envelope and has its own 2 s debounce matching
    // iOS's `sendDebounce` (`TypingIndicatorManager.swift:96`). `GroupChatViewModel`
    // and `GroupsViewModel` both call it directly.
    //
    // The INBOUND half stays here: `_groupTypingStatus` and `handleIncomingTyping`
    // still route group pings to the right conversation.

    /**
     * Check if peer is typing (1-on-1)
     */
    fun isTyping(publicKey: String): Boolean {
        return _typingStatus.value[publicKey] == true
    }

    /**
     * Check if content is a typing indicator (Android or iOS prefix)
     */
    fun isTypingMessage(content: String): Boolean =
        OSHIControlPayload.matchedPrefix(content).let {
            it == TYPING_PREFIX || it == TYPING_PREFIX_IOS
        }

    /**
     * Handle incoming typing indicator (Android ✍️TYPING✍️ or iOS ⌨️TYPING⌨️).
     * Routes to either 1-on-1 or group state based on presence of "groupId".
     */
    fun handleIncomingTypingIndicator(content: String, senderPublicKey: String) {
        // stripPrefix (not removePrefix) so a peer emitting the BARE codepoint
        // (⌨TYPING⌨ with no U+FE0F — older iOS builds do) still parses instead of
        // leaving the sentinel glued to the JSON.
        if (!isTypingMessage(content)) return
        val jsonStr = OSHIControlPayload.stripPrefix(content)

        try {
            val json = JSONObject(jsonStr)
            val isTyping = json.getBoolean("isTyping")
            val groupId = json.optString("groupId", "").takeIf { it.isNotEmpty() }

            if (groupId != null) {
                updateGroupTypingStatus(groupId, senderPublicKey, isTyping)
                Log.d(TAG, "Received group typing from ${senderPublicKey.take(8)}... in group ${groupId.take(8)}: $isTyping")
            } else {
                updateTypingStatus(senderPublicKey, isTyping)
                Log.d(TAG, "Received typing indicator from ${senderPublicKey.take(8)}...: $isTyping")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse typing indicator: ${e.message}")
        }
    }

    /**
     * Add callback for typing status changes
     */
    fun addTypingCallback(callback: (String, Boolean) -> Unit) {
        typingCallbacks.add(callback)
    }

    /**
     * Remove callback
     */
    fun removeTypingCallback(callback: (String, Boolean) -> Unit) {
        typingCallbacks.remove(callback)
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    private fun sendTypingIndicator(
        recipientPublicKey: String,
        isTyping: Boolean,
        groupId: String? = null
    ) {
        scope.launch {
            // Respect the Typing Indicators privacy setting (previously ignored —
            // the toggle persisted but nothing consumed it, so a user who turned
            // it OFF still broadcast typing). iOS gates this the same way.
            val typingEnabled = try {
                context.dataStore.data.first()[booleanPreferencesKey("typing_indicators_enabled")] ?: true
            } catch (_: Exception) { true }
            if (!typingEnabled) return@launch
            val json = buildTypingPayload(
                senderPublicKey = try { cryptoManager.getPublicKeyString() } catch (_: Exception) { "" },
                isTyping = isTyping,
                appleTimestamp = nowAsAppleTimestamp(),
                groupId = groupId
            )

            // Send the iOS prefix (⌨️TYPING⌨️): iOS only accepts that form
            // (TypingIndicatorManager.swift:15,54), so the old Android-only ✍️
            // prefix meant an iPhone never saw an Android user typing. Android
            // parses BOTH prefixes on receive, so this stays Android↔Android-safe.
            val content = "$TYPING_PREFIX_IOS${json}"

            try {
                // ⌨️TYPING⌨️ is on iOS's silentControlPrefixes list
                // (MessageManager.swift:4664-4669), so iOS never wakes the peer for it.
                // Android pushed unconditionally: opening a chat and typing buzzed the
                // far phone with "New Message" and nothing behind the banner.
                //
                // The String overload encrypts the payload (VPSClient.kt): it used to
                // pass the bytes through as if they were ciphertext, and the receiver —
                // MessageRepository.decryptIOSMessage — then failed the AES-GCM tag check
                // and dropped the ping. This send only ever arrives because it is sealed.
                // __CONTROL_OVER_V2_2026_10_28__ V2 first; the legacy lane is the
                // fallback and is unchanged. `sendText` returns false when V2 is off,
                // when the peer has no published bundle, or when the relay is
                // unreachable, so a build with V2 disabled behaves exactly as before.
                //
                // No push on either lane: ⌨️TYPING⌨️ is on the silent-control list, and
                // `V2MessageRouter.sendText` does not push by itself — `pushAfterV2Send`
                // is a separate call in MessageRepository, and nothing here makes it.
                com.oshi.messenger.network.v2.ControlLane.preferV2(
                    v2 = {
                        v2MessageRouter.sendText(
                            recipientPublicKey,
                            content.toByteArray(Charsets.UTF_8),
                            java.util.UUID.randomUUID().toString(),
                        )
                    },
                    legacy = { vpsClient.sendMessage(recipientPublicKey, content, suppressPush = true) },
                    onV2Error = { Log.w(TAG, "V2 typing send threw, using legacy lane: ${it.message}") },
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send typing indicator: ${e.message}")
            }
        }
    }

    private fun updateTypingStatus(publicKey: String, isTyping: Boolean) {
        // Cancel existing timeout
        typingTimeouts[publicKey]?.cancel()

        if (isTyping) {
            // Set typing status
            val current = _typingStatus.value.toMutableMap()
            current[publicKey] = true
            _typingStatus.value = current

            // Start timeout to auto-clear
            typingTimeouts[publicKey] = scope.launch {
                delay(TYPING_TIMEOUT_MS)
                clearTypingStatus(publicKey)
            }
        } else {
            clearTypingStatus(publicKey)
        }

        // Notify callbacks
        typingCallbacks.forEach { it(publicKey, isTyping) }
    }

    private fun clearTypingStatus(publicKey: String) {
        val current = _typingStatus.value.toMutableMap()
        current.remove(publicKey)
        _typingStatus.value = current

        typingTimeouts[publicKey]?.cancel()
        typingTimeouts.remove(publicKey)

        // Notify callbacks
        typingCallbacks.forEach { it(publicKey, false) }
    }

    private fun updateGroupTypingStatus(groupId: String, senderPublicKey: String, isTyping: Boolean) {
        val timeoutKey = "$groupId|$senderPublicKey"
        typingTimeouts[timeoutKey]?.cancel()

        if (isTyping) {
            val current = _groupTypingStatus.value.toMutableMap()
            val group = current[groupId]?.toMutableSet() ?: mutableSetOf()
            group.add(senderPublicKey)
            current[groupId] = group
            _groupTypingStatus.value = current

            typingTimeouts[timeoutKey] = scope.launch {
                delay(TYPING_TIMEOUT_MS)
                clearGroupTypingStatus(groupId, senderPublicKey)
            }
        } else {
            clearGroupTypingStatus(groupId, senderPublicKey)
        }
    }

    private fun clearGroupTypingStatus(groupId: String, senderPublicKey: String) {
        val current = _groupTypingStatus.value.toMutableMap()
        val group = current[groupId]?.toMutableSet() ?: return
        group.remove(senderPublicKey)
        if (group.isEmpty()) current.remove(groupId) else current[groupId] = group
        _groupTypingStatus.value = current

        val timeoutKey = "$groupId|$senderPublicKey"
        typingTimeouts[timeoutKey]?.cancel()
        typingTimeouts.remove(timeoutKey)
    }

    fun cleanup() {
        scope.cancel()
        typingTimeouts.values.forEach { it.cancel() }
        typingTimeouts.clear()
        typingCallbacks.clear()
    }
}
