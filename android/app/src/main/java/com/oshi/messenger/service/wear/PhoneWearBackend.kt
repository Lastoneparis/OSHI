package com.oshi.messenger.service.wear

import android.content.Context
import com.oshi.messenger.R
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.data.model.ReactionToggle
import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.service.MessageReactionManager
import com.oshi.messenger.service.MessageSendPolicy
import com.oshi.messenger.service.NotificationPresentation
import com.oshi.messenger.service.WelcomeMessage
import com.oshi.messenger.service.lora.LoRaNodeIdentityMap
import com.oshi.messenger.service.media.GifBytes
import com.oshi.messenger.ui.theme.AvatarIdentity
import com.oshi.messenger.ui.viewmodel.SettingsViewModel
import com.oshi.messenger.ui.viewmodel.dataStore
import com.oshi.wearbridge.WearConvKind
import com.oshi.wearbridge.WearConversation
import com.oshi.wearbridge.WearDelivery
import com.oshi.wearbridge.WearNet
import com.oshi.wearbridge.WearMedia
import com.oshi.wearbridge.WearMessage
import com.oshi.wearbridge.WearProtocol
import com.oshi.wearbridge.WearStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * __WEAR_BRIDGE_2026_11_17__ The phone side of the watch bridge: reads the
 * local database and calls the app's EXISTING send / reaction code. Nothing
 * here encrypts, routes or speaks a protocol of its own:
 *
 *  - 1:1 text   → [MessageRepository.sendTextMessage] (exactly what
 *                 `ChatViewModel.sendMessage` and the notification inline
 *                 reply `NotificationActionReceiver.handleReply` call), or
 *                 [MessageRepository.sendLoRaInteropReply] for a bare LoRa
 *                 node thread, as `ChatViewModel.sendMessage` does;
 *  - group text → [GroupManager.sendGroupMessage] (`GroupChatViewModel.sendMessage`);
 *  - 1:1 react  → [MessageReactionManager.addReaction] (ChatScreen's quick bar);
 *  - group react→ [GroupManager.broadcastReaction] with the same toggle rule
 *                 as `GroupChatViewModel.toggleReaction` ([ReactionToggle]).
 */
class PhoneWearBackend(
    private val context: Context,
    private val repo: MessageRepository,
    private val groups: GroupManager,
    private val reactions: MessageReactionManager,
    private val crypto: CryptoManager,
    private val mesh: com.oshi.messenger.network.mesh.MeshNetworkManager? = null,
) : WearBridgeBackend {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun messageRepository(): MessageRepository
        fun groupManager(): GroupManager
        fun reactionManager(): MessageReactionManager
        fun cryptoManager(): CryptoManager
        fun meshNetworkManager(): com.oshi.messenger.network.mesh.MeshNetworkManager
    }

    companion object {
        /** Settings → Smartwatch; reads the store, never a cached flow. */
        suspend fun isEnabled(context: Context): Boolean =
            runCatching { context.dataStore.data.first()[SettingsViewModel.KEY_SMARTWATCH_ENABLED] }
                .getOrNull() ?: false

        fun create(context: Context): PhoneWearBackend {
            val d = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
            return PhoneWearBackend(
                context.applicationContext, d.messageRepository(), d.groupManager(),
                d.reactionManager(), d.cryptoManager(),
                runCatching { d.meshNetworkManager() }.getOrNull(),
            )
        }

        /** How long the watch waits for a send before being told "sending" anyway. */
        private const val SEND_WAIT_MS = 8_000L

        /** Sends outlive the request: never cancelled because the watch stopped waiting. */
        private val sendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override suspend fun isEnabled(): Boolean = isEnabled(context)

    override suspend fun isLocked(): Boolean {
        val prefs = runCatching { context.dataStore.data.first() }.getOrNull() ?: return true
        return WearLockState.isLocked(
            biometricEnabled = prefs[SettingsViewModel.KEY_BIOMETRIC_ENABLED] ?: false,
            autoLockTimeoutSeconds = prefs[SettingsViewModel.KEY_AUTO_LOCK_TIMEOUT_SECONDS] ?: 0,
        )
    }

    override fun isReady(): Boolean = crypto.hasKeyPair() && repo.isPipelineInitialized

    // ---- reading ----------------------------------------------------------

    private val labels by lazy { OSHIControlPayload.labels(context) }
    private val attachment by lazy { context.getString(R.string.notification_sent_attachment) }

    private fun mediaOf(type: MediaType?, isGif: Boolean, isVideoNote: Boolean): WearMedia? = when (type) {
        null -> null
        MediaType.IMAGE -> if (isGif) WearMedia.GIF else WearMedia.PHOTO
        MediaType.VIDEO -> WearMedia.VIDEO
        MediaType.AUDIO -> WearMedia.VOICE
        MediaType.DOCUMENT -> WearMedia.FILE
        MediaType.LOCATION -> WearMedia.LOCATION
        MediaType.CONTACT -> WearMedia.CONTACT
    }

    private fun mediaLabel(m: WearMedia): String = when (m) {
        WearMedia.PHOTO -> "📷 " + labels.mediaImage
        WearMedia.VIDEO -> "🎥 " + labels.mediaVideo
        WearMedia.VOICE -> "🎤 " + labels.mediaAudio
        WearMedia.GIF -> "GIF"
        WearMedia.FILE -> "📄 " + labels.mediaDocument
        WearMedia.LOCATION -> "📍 " + labels.locationShared
        WearMedia.CONTACT -> "👤 " + attachment
    }

    /**
     * The text of one message for the watch, or null when the message is pure
     * protocol (the banner logic would post nothing, so the watch shows nothing).
     * A media message keeps only a typed caption: its placeholder is drawn by
     * the watch from [WearMedia].
     */
    private fun textOf(raw: String, media: WearMedia?): String? {
        if (media != null) {
            val plainCaption = raw.isNotBlank() && OSHIControlPayload.displaySummary(raw, labels) == null
            return if (plainCaption) raw else ""
        }
        return when (val d = NotificationPresentation.decide(raw, labels, attachment)) {
            is NotificationPresentation.Decision.Suppress -> null
            is NotificationPresentation.Decision.Show -> d.body
        }
    }

    private fun previewOf(raw: String, media: WearMedia?): String {
        val text = textOf(raw, media)
        return when {
            media != null && text.isNullOrBlank() -> mediaLabel(media)
            media != null -> mediaLabel(media) + " · " + text
            else -> text ?: ""
        }
    }

    private fun dmName(alias: String, key: String): String = alias.trim().ifBlank { key.take(8) }

    private fun canReplyDirect(key: String): Boolean = key != WelcomeMessage.LEGACY_SYSTEM_SENDER_KEY

    /** Same store and keys as ConversationsViewModel (`pinned_conversation_ids`). */
    private suspend fun pinnedIds(): Set<String> = runCatching {
        context.dataStore.data.first()[
            androidx.datastore.preferences.core.stringSetPreferencesKey("pinned_conversation_ids")
        ]
    }.getOrNull().orEmpty()

    override suspend fun conversations(limit: Int): List<WearConversation> {
        val pinned = pinnedIds()
        val dms = withTimeout(5_000) { repo.getConversations().first() }
            .filter { it.hasMessages }
            .map { c ->
                val m = c.lastMessage
                val media = mediaOf(m.mediaType, GifBytes.isGif(m.mediaData), m.isVideoNote)
                val raw = if (m.isViewOnce) "" else (m.editedContent ?: m.content)
                WearConversation(
                    kind = WearConvKind.DIRECT,
                    id = c.participantPublicKey,
                    name = dmName(c.participantAlias, c.participantPublicKey),
                    preview = if (m.isDeletedForEveryone) "" else previewOf(raw, media),
                    unread = c.unreadCount,
                    timestamp = m.timestamp,
                    avatarKey = AvatarIdentity.contactKey(c.participantPublicKey),
                    lastFromMe = m.isFromMe,
                    canReply = canReplyDirect(c.participantPublicKey),
                    pinned = com.oshi.messenger.data.model.ConversationPinOrder.isPinned(c.participantPublicKey, pinned),
                )
            }
        val grp = groups.groupConversations.value.map { gc ->
            val m = gc.lastMessage
            val media = m?.let { mediaOf(it.mediaType, it.mediaFileName.isGifName() || GifBytes.isGif(it.mediaData), false) }
            val body = m?.takeUnless { it.isDeleted }?.let { previewOf(it.editedContent ?: it.content, media) }.orEmpty()
            val who = m?.takeIf { !it.isFromMe && body.isNotBlank() }?.senderName?.trim()?.takeIf { it.isNotEmpty() }
            WearConversation(
                kind = WearConvKind.GROUP,
                id = gc.group.id,
                name = gc.group.name.ifBlank { gc.group.id.take(8) },
                preview = if (who != null) "$who: $body" else body,
                unread = gc.unreadCount,
                timestamp = m?.timestamp ?: gc.group.createdAt,
                avatarKey = AvatarIdentity.groupKey(gc.group.id),
                lastFromMe = m?.isFromMe == true,
                pinned = com.oshi.messenger.data.model.ConversationPinOrder.isPinned(
                    com.oshi.messenger.data.model.ChatListMerge.pinKeyForGroup(gc.group.id), pinned,
                ),
            )
        }
        // Recency first, then the phone's pins on top (stable sort), as the phone list does.
        return (dms + grp).sortedByDescending { it.timestamp }.sortedBy { if (it.pinned) 0 else 1 }.take(limit)
    }

    private fun String?.isGifName(): Boolean = this?.lowercase()?.endsWith(".gif") == true

    private suspend fun findDirect(id: String): com.oshi.messenger.data.model.Conversation? {
        val canon = MessageRepository.canonicalIdentity(id)
        return withTimeout(5_000) { repo.getConversations().first() }
            .firstOrNull { MessageRepository.canonicalIdentity(it.participantPublicKey) == canon }
    }

    override suspend fun thread(kind: WearConvKind, id: String, limit: Int): WearBridgeBackend.Thread? =
        when (kind) {
            WearConvKind.DIRECT -> {
                val conv = findDirect(id) ?: return null
                val key = conv.participantPublicKey
                val rows = withTimeout(5_000) { repo.getThreadMessagesForContact(key).first() }
                val msgs = rows.sortedBy { it.timestamp }
                    .asSequence()
                    .filterNot { it.isDeleted || it.isDeletedForEveryone }
                    .mapNotNull { m ->
                        val media = mediaOf(m.mediaType, GifBytes.isGif(m.mediaData), m.isVideoNote)
                        // A view-once message is never shown on the wrist: its whole
                        // point is that it is opened once, deliberately, on the phone.
                        val text = if (m.isViewOnce) "👁" else textOf(m.editedContent ?: m.content, media)
                        text ?: return@mapNotNull null
                        val summary = reactions.getReactionSummary(m.id)
                        WearMessage(
                            id = m.id,
                            fromMe = m.isFromMe,
                            text = text,
                            media = if (m.isViewOnce) null else media,
                            timestamp = m.timestamp,
                            reactions = summary.reactions.filterValues { it.isNotEmpty() }.keys.take(3),
                            myReaction = summary.myReaction,
                            delivery = if (m.isFromMe) deliveryOf(m) else null,
                            hasThumb = !m.isViewOnce && media == WearMedia.PHOTO,
                            durationSec = if (media == WearMedia.VOICE) WearVoiceDuration.fromCaption(m.content) else 0,
                        )
                    }.toList().takeLast(limit)
                WearBridgeBackend.Thread(dmName(conv.participantAlias, key), canReplyDirect(key), msgs)
            }
            WearConvKind.GROUP -> {
                val group = groups.getGroupById(id) ?: return null
                val me = crypto.getPublicKeyString()
                val all = groups.groupMessages.value.entries
                    .firstOrNull { it.key.equals(group.id, ignoreCase = true) }?.value.orEmpty()
                val msgs = all.sortedBy { it.timestamp }
                    .asSequence()
                    .filterNot { it.isDeleted }
                    .mapNotNull { m ->
                        val media = mediaOf(m.mediaType, m.mediaFileName.isGifName() || GifBytes.isGif(m.mediaData), false)
                        val text = textOf(m.editedContent ?: m.content, media) ?: return@mapNotNull null
                        WearMessage(
                            id = m.id,
                            fromMe = m.isFromMe,
                            sender = if (m.isFromMe) null else m.senderName.trim().ifBlank { m.senderPublicKey.take(8) },
                            senderKey = if (m.isFromMe) null else AvatarIdentity.contactKey(m.senderPublicKey),
                            text = text,
                            media = media,
                            timestamp = m.timestamp,
                            reactions = m.reactions.values.filter { it.isNotBlank() }.distinct().take(3),
                            myReaction = m.reactions.entries.firstOrNull { it.key.equals(me, ignoreCase = true) }?.value,
                            hasThumb = media == WearMedia.PHOTO,
                            durationSec = if (media == WearMedia.VOICE) ((m.mediaDurationMs ?: 0L) / 1000L).toInt() else 0,
                        )
                    }.toList().takeLast(limit)
                WearBridgeBackend.Thread(group.name, true, msgs)
            }
        }

    // ---- writing: the existing paths --------------------------------------

    override suspend fun send(kind: WearConvKind, id: String, text: String): WearStatus {
        val content = MessageSendPolicy.normalizeLineBreaks(text)
        if (content.isBlank() || content.length > WearProtocol.MAX_SEND_CHARS) return WearStatus.INVALID
        return when (kind) {
            WearConvKind.DIRECT -> {
                val conv = findDirect(id) ?: return WearStatus.NOT_FOUND
                val key = conv.participantPublicKey
                if (!canReplyDirect(key)) return WearStatus.READ_ONLY
                val job = sendScope.async {
                    val result = if (LoRaNodeIdentityMap.isFallbackConversationKey(key)) {
                        repo.sendLoRaInteropReply(key, content)
                    } else {
                        repo.sendTextMessage(key, content)
                    }
                    // Same as the notification inline reply: answering means read.
                    runCatching { repo.markConversationAsRead(key) }
                    result.isSuccess
                }
                when (withTimeoutOrNull(SEND_WAIT_MS) { job.await() }) {
                    false -> WearStatus.ERROR
                    else -> WearStatus.OK // true, or still going (row stored, retried by the pipeline)
                }
            }
            WearConvKind.GROUP -> {
                val group = groups.getGroupById(id) ?: return WearStatus.NOT_FOUND
                groups.sendGroupMessage(group.id, content)
                groups.markGroupMessagesAsRead(group.id)
                WearStatus.OK
            }
        }
    }

    override suspend fun react(kind: WearConvKind, id: String, messageId: String, emoji: String): WearStatus {
        if (emoji !in WearProtocol.QUICK_REACTIONS) return WearStatus.INVALID
        return when (kind) {
            WearConvKind.DIRECT -> {
                val conv = findDirect(id) ?: return WearStatus.NOT_FOUND
                val key = conv.participantPublicKey
                val rows = withTimeout(5_000) { repo.getThreadMessagesForContact(key).first() }
                if (rows.none { it.id == messageId }) return WearStatus.NOT_FOUND
                // ChatScreen's quick bar: `reactionManager.addReaction(messageId, emoji, recipientPublicKey)`
                // (it toggles: the same emoji again removes it).
                reactions.addReaction(messageId = messageId, emoji = emoji, recipientPublicKey = key)
                WearStatus.OK
            }
            WearConvKind.GROUP -> {
                val group = groups.getGroupById(id) ?: return WearStatus.NOT_FOUND
                val msgs = groups.groupMessages.value.entries
                    .firstOrNull { it.key.equals(group.id, ignoreCase = true) }?.value.orEmpty()
                val target = msgs.firstOrNull { it.id.equals(messageId, ignoreCase = true) }
                    ?: return WearStatus.NOT_FOUND
                val me = crypto.getPublicKeyString()
                val mine = target.reactions.entries.firstOrNull { it.key.equals(me, ignoreCase = true) }?.value
                // GroupChatViewModel.toggleReaction / removeReaction, same calls.
                if (ReactionToggle.next(mine, emoji) == null) {
                    groups.broadcastReaction(group.id, target.id, mine.orEmpty(), isRemoval = true)
                } else {
                    groups.broadcastReaction(group.id, target.id, emoji, isRemoval = false)
                }
                WearStatus.OK
            }
        }
    }

    // ---- __WEAR_POLISH_2026_11_25__ read / preview / voice / network ---------

    private fun deliveryOf(m: com.oshi.messenger.data.model.Message): WearDelivery = when {
        m.isFailed -> WearDelivery.FAILED
        m.isRead -> WearDelivery.READ
        m.isDelivered -> WearDelivery.DELIVERED
        else -> WearDelivery.SENT
    }

    override suspend fun markRead(kind: WearConvKind, id: String): WearStatus = when (kind) {
        WearConvKind.DIRECT -> {
            val conv = findDirect(id)
            if (conv == null) WearStatus.NOT_FOUND else {
                // The same call as opening the chat on the phone (and the notification's
                // "Mark as read"): flips the rows, clears the banner, sends the receipt.
                repo.markConversationAsRead(conv.participantPublicKey)
                WearStatus.OK
            }
        }
        WearConvKind.GROUP -> {
            val group = groups.getGroupById(id)
            if (group == null) WearStatus.NOT_FOUND else {
                groups.markGroupMessagesAsRead(group.id)
                WearStatus.OK
            }
        }
    }

    override suspend fun thumbnail(kind: WearConvKind, id: String, messageId: String): ByteArray? {
        val bytes: ByteArray = when (kind) {
            WearConvKind.DIRECT -> {
                val conv = findDirect(id) ?: return null
                val rows = withTimeout(5_000) { repo.getThreadMessagesForContact(conv.participantPublicKey).first() }
                // Only a message of THIS conversation, only a photo, NEVER a view-once one.
                val m = rows.firstOrNull { it.id == messageId } ?: return null
                if (m.isViewOnce || m.isDeleted || m.isDeletedForEveryone) return null
                if (mediaOf(m.mediaType, GifBytes.isGif(m.mediaData), m.isVideoNote) != WearMedia.PHOTO) return null
                repo.loadMediaData(m.id) ?: return null
            }
            WearConvKind.GROUP -> {
                val group = groups.getGroupById(id) ?: return null
                val m = groups.groupMessages.value.entries
                    .firstOrNull { it.key.equals(group.id, ignoreCase = true) }?.value.orEmpty()
                    .firstOrNull { it.id.equals(messageId, ignoreCase = true) } ?: return null
                if (m.isDeleted || m.mediaType != MediaType.IMAGE) return null
                m.mediaData ?: kotlinx.coroutines.withContext(Dispatchers.IO) { groups.loadMediaBytes(m.id) } ?: return null
            }
        }
        return kotlinx.coroutines.withContext(Dispatchers.Default) { WearThumbnail.jpeg(bytes) }
    }

    override suspend fun sendVoice(kind: WearConvKind, id: String, audio: ByteArray, durationMs: Long): WearStatus {
        if (!com.oshi.wearbridge.WearVoiceFrame.looksLikeM4a(audio) || audio.size > WearProtocol.MAX_VOICE_BYTES) {
            return WearStatus.INVALID
        }
        val ms = durationMs.coerceIn(WearProtocol.MIN_VOICE_MS, WearProtocol.MAX_VOICE_MS)
        return when (kind) {
            WearConvKind.DIRECT -> {
                val conv = findDirect(id) ?: return WearStatus.NOT_FOUND
                val key = conv.participantPublicKey
                if (!canReplyDirect(key)) return WearStatus.READ_ONLY
                // A radio thread is text-only (the radio cannot carry audio): Meshtastic node
                // (`lora!`), LoRa channel, and — release 1.7.4 integration — MeshCore user/channel,
                // which the phone's own composer also keeps text-only (ChatViewModel).
                if (!WearVoiceRoute.carriesAudio(key)) return WearStatus.READ_ONLY
                val job = sendScope.async {
                    // ChatViewModel.sendVoiceMessage, same call and caption shape.
                    val result = repo.sendMediaMessage(
                        recipientPublicKey = key,
                        mediaType = MediaType.AUDIO,
                        mediaData = audio,
                        caption = "[AUDIO] " + WearVoiceDuration.caption(ms),
                    )
                    runCatching { repo.markConversationAsRead(key) }
                    result.isSuccess
                }
                when (withTimeoutOrNull(SEND_WAIT_MS) { job.await() }) {
                    false -> WearStatus.ERROR
                    else -> WearStatus.OK
                }
            }
            WearConvKind.GROUP -> {
                val group = groups.getGroupById(id) ?: return WearStatus.NOT_FOUND
                // GroupChatViewModel.sendVoiceMessage: empty body, duration in its own column.
                groups.sendGroupMessage(
                    groupId = group.id, content = "", mediaType = MediaType.AUDIO,
                    mediaData = audio, mediaDurationMs = ms,
                )
                groups.markGroupMessagesAsRead(group.id)
                WearStatus.OK
            }
        }
    }

    override fun strictGlance(): Boolean = com.oshi.messenger.service.MessageBanner.anonymous(context)

    override fun network(): WearNet? = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val internet = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val lora = runCatching {
            com.oshi.messenger.service.lora.MeshtasticManager.getInstance(context).state.value ==
                com.oshi.messenger.service.lora.MeshtasticManager.ConnState.READY
        }.getOrDefault(false) ||
            // Release 1.7.4 integration: a MeshCore radio alone is a ready radio too.
            com.oshi.messenger.service.lora.MeshCoreManager.instanceOrNull?.isReady == true
        // Release 1.7.4 integration: a phone reached over Nearby AND Wi-Fi Aware is listed twice
        // in connectedPeers ("aware:<key>" + the Nearby endpoint); count people, not links.
        val peers = mesh?.connectedPeers?.value?.distinctBy { it.publicKey.ifEmpty { it.id } }?.size ?: 0
        WearNet(internet = internet, meshPeers = peers, lora = lora)
    }.getOrNull()
}

/** Whether a 1:1 thread key can take a voice message from the watch (radio-only threads cannot). */
object WearVoiceRoute {
    fun carriesAudio(key: String): Boolean =
        !LoRaNodeIdentityMap.isFallbackConversationKey(key) &&
            !com.oshi.messenger.service.lora.LoRaChannelThread.isChannelKey(key) &&
            !com.oshi.messenger.service.lora.MeshCoreThread.isMeshCoreKey(key)
}

/** "[AUDIO] M:SS" — the 1:1 voice caption the phone has always written (ChatViewModel). */
object WearVoiceDuration {
    private val CAPTION = Regex("""\[AUDIO]\s*(\d+):(\d{2})""")

    fun caption(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    fun fromCaption(content: String?): Int {
        val m = CAPTION.find(content ?: return 0) ?: return 0
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }
}
