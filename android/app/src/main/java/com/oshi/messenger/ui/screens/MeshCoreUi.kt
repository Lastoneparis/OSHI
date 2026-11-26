package com.oshi.messenger.ui.screens

import com.oshi.messenger.R
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.service.lora.MeshCore
import com.oshi.messenger.service.lora.MeshCoreManager
import com.oshi.messenger.service.lora.MeshCoreText
import com.oshi.messenger.service.lora.MeshCoreThread

/**
 * __MESHCORE_INTEROP_2026_11_25__ What a person sees of MeshCore, as pure decisions (JVM-tested): the
 * bubble's state, the composer's counter, the wording of a connection error. The composables
 * (MeshCoreSection, MeshCoreChatParts, ChatScreen) only draw these.
 */
object MeshCoreUi {

    /** An outgoing MeshCore bubble: clock, one tick, two ticks, or "not delivered · tap to retry". */
    enum class BubbleStatus { SENDING, SENT, DELIVERED, FAILED }

    /**
     * [pending] = ids the radio has not taken yet ([MeshCoreManager.pendingTexts]). A channel message never
     * gets past SENT: nobody acknowledges a broadcast.
     */
    fun bubbleStatus(message: Message, pending: Set<String>): BubbleStatus = when {
        message.isFailed -> BubbleStatus.FAILED
        message.isDelivered -> BubbleStatus.DELIVERED
        message.id in pending -> BubbleStatus.SENDING
        else -> BubbleStatus.SENT
    }

    /**
     * The composer's counter for a MeshCore thread: [chars] of [limit] for one radio message, and how many
     * numbered messages the text will go as. Shown once the text is near the limit ([visible]).
     */
    data class ComposerCount(val chars: Int, val limit: Int, val parts: Int, val tooLong: Boolean = false) {
        val visible: Boolean get() = tooLong || parts > 1 || chars >= limit - WARN_BEFORE
        val over: Boolean get() = tooLong || parts > 1
    }

    const val WARN_BEFORE = 20

    fun composerCount(text: String, isChannel: Boolean, selfName: String?): ComposerCount {
        val budget = if (isChannel) MeshCoreText.channelMaxBytes(selfName) else MeshCoreText.DM_MAX_BYTES
        // The very function the radio session splits with (MeshCoreText.split, shared rule with iOS).
        val split = if (text.isBlank()) emptyList() else MeshCoreText.split(text, budget)
        return ComposerCount(
            text.codePointCount(0, text.length), MeshCore.RECOMMENDED_TEXT_CHARS,
            split?.size ?: 0, tooLong = split == null,
        )
    }

    /** The sentence for a [MeshCoreManager.lastError] key, or null for none / unknown. */
    fun errorText(error: String?): Int? = when (error) {
        MeshCoreManager.ERROR_PAIRING_FAILED -> R.string.meshcore_error_pairing
        MeshCoreManager.ERROR_NO_ANSWER -> R.string.meshcore_error_no_answer
        MeshCoreManager.ERROR_CONNECTION_LOST -> R.string.meshcore_state_reconnecting
        MeshCoreManager.ERROR_NO_FREE_CHANNEL -> R.string.meshcore_no_free_channel_chat
        else -> null
    }

    /** The radio row's status line. */
    fun statusText(state: MeshCoreManager.ConnState, awaitingPin: Boolean, chatReady: Boolean, paired: Boolean): Int? = when {
        awaitingPin -> R.string.meshcore_state_pin
        state == MeshCoreManager.ConnState.READY && chatReady -> null   // the "seen as" line says it
        state == MeshCoreManager.ConnState.CONFIGURING || state == MeshCoreManager.ConnState.READY -> R.string.meshcore_state_reading
        state == MeshCoreManager.ConnState.CONNECTING -> R.string.meshcore_state_connecting
        state == MeshCoreManager.ConnState.SCANNING -> R.string.meshtastic_searching
        paired -> R.string.meshcore_state_connecting
        else -> null
    }

    /** The note above the composer: what kind of conversation this is, in one calm sentence. */
    fun threadNote(key: String, channels: List<MeshCore.ChannelInfo>): Int = when {
        !MeshCoreThread.isChannelKey(key) -> R.string.meshcore_note_dm
        MeshCoreThread.isPublicChannelKey(key, channels) -> R.string.meshcore_note_public
        else -> R.string.meshcore_note_private
    }

    /** A channel bubble's speaker, split off the "<name>: " MeshCore apps put in front of the text. */
    fun channelSpeaker(content: String): Pair<String?, String> = MeshCore.splitChannelSender(content)

    /**
     * A DM that failed while the radio is connected went unanswered 4 times: most likely out of range. With no
     * radio, the banner above the composer already says why, and the bubble keeps the usual words.
     */
    fun failedText(message: Message, radioConnected: Boolean): Int =
        if (radioConnected && MeshCoreThread.isContactKey(message.recipientPublicKey)) R.string.meshcore_failed_dm
        else R.string.chat_not_delivered
}
