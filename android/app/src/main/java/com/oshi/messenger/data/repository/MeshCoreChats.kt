package com.oshi.messenger.data.repository

import com.oshi.messenger.data.model.DeliveryMethod
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.service.lora.MeshCoreChatRadio
import com.oshi.messenger.service.lora.MeshCoreInboundText
import com.oshi.messenger.service.lora.MeshCoreSendOutcome
import com.oshi.messenger.service.lora.MeshCoreSession
import com.oshi.messenger.service.lora.MeshCoreThread
import java.util.UUID

/**
 * __MESHCORE_INTEROP_2026_11_25__ People on plain MeshCore apps, as chats: what used to live inline in
 * MessageRepository, pulled out so the whole path radio → rows → what the screen shows runs in a JVM test
 * (MeshCoreEndToEndTest) against frames captured from real MeshCore firmware.
 *
 * Their text is MeshCore's own AES-128 + 2-byte MAC, NOT OSHI end-to-end encryption, so every row is
 * [DeliveryMethod.LORA_PLAINTEXT] and the thread says so. Keys are `meshcore!<hex>` / `mcchan!<name>`
 * ([MeshCoreThread]), never the Meshtastic `lora!` families.
 */
class MeshCoreChats(
    private val store: Store,
    private val radio: () -> MeshCoreChatRadio?,
    private val myKey: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** The few storage and notification calls this needs; MessageRepository implements them on Room. */
    interface Store {
        suspend fun insert(message: Message)
        suspend fun message(id: String): Message?
        suspend fun update(message: Message)
        suspend fun contactAlias(key: String): String?
        suspend fun insertContact(key: String, alias: String)
        suspend fun renameContact(key: String, alias: String)
        suspend fun isBlocked(key: String): Boolean
        /** A banner for [key] (its tap opens `chat/<key>`); MessageRepository.showMessageNotification. */
        suspend fun notify(key: String, preview: String)
        /** Mirror to a linked web session, if any. */
        fun mirror(message: Message) {}
    }

    /** Text a MeshCore user wrote: a row in their thread (or the channel's), the thread listed, a banner. */
    suspend fun receive(inbound: MeshCoreInboundText) {
        if (inbound.text.isEmpty()) return
        if (store.isBlocked(inbound.conversationKey)) return
        val isChannel = inbound.channelKey != null
        val message = Message(
            id = newId(),
            // A channel has no authenticated speaker: the row comes FROM the room, and keeps the name the
            // sender's app wrote in front of the text, exactly as MeshCore apps show it.
            senderPublicKey = inbound.conversationKey,
            recipientPublicKey = myKey(),
            content = if (isChannel && inbound.senderName != null) "${inbound.senderName}: ${inbound.text}" else inbound.text,
            timestamp = now(),
            isFromMe = false,
            isDelivered = true,
            deliveryMethod = DeliveryMethod.LORA_PLAINTEXT,
        )
        store.insert(message)
        ensureContact(
            inbound.conversationKey,
            if (isChannel) MeshCoreThread.channelName(inbound.conversationKey).orEmpty()
            else inbound.senderName ?: MeshCoreThread.displayName(inbound.conversationKey),
        )
        store.mirror(message)
        store.notify(inbound.conversationKey, message.content)
    }

    /**
     * Write to a MeshCore user or channel; the MeshCore radio is the ONLY route. The row always exists, so
     * nothing typed is lost: with no radio ready (or a channel this radio does not have) it is stored FAILED,
     * and the bubble offers tap-to-retry while the thread says the radio is not connected. A direct message is
     * SENT once the radio took it and DELIVERED when the recipient's radio acknowledged it; a channel message
     * is never acknowledged, so it stays sent.
     */
    suspend fun send(key: String, content: String, resendOf: Message? = null): Message {
        val r = radio()
        val message = Message(
            id = resendOf?.id ?: newId(),
            senderPublicKey = myKey(),
            recipientPublicKey = key,
            content = content,
            timestamp = resendOf?.timestamp ?: now(),
            isFromMe = true,
            isRead = false,
            isDelivered = false,
            deliveryMethod = DeliveryMethod.LORA_PLAINTEXT,
        )
        store.insert(message)
        store.mirror(message)
        ensureContact(key, MeshCoreThread.displayName(key, r?.contacts?.value.orEmpty()))
        val queued = when {
            r == null || !r.isChatReady -> false
            MeshCoreThread.isChannelKey(key) -> r.channelSlotFor(key) != null && r.sendChannelText(message.id, key, content)
            else -> r.sendDirectText(message.id, key, content)
        }
        if (!queued) {
            val failed = message.copy(isFailed = true)
            store.update(failed)
            return failed
        }
        return message
    }

    /** The radio moved a message on. SENT changes nothing stored: the bubble's clock comes from the radio's queue. */
    suspend fun apply(outcome: MeshCoreSendOutcome) {
        val row = store.message(outcome.messageId) ?: return
        when (outcome.state) {
            MeshCoreSession.SendState.SENT -> Unit
            MeshCoreSession.SendState.DELIVERED ->
                if (!row.isDelivered || row.isFailed) store.update(row.copy(isDelivered = true, isFailed = false))
            MeshCoreSession.SendState.FAILED ->
                if (!row.isDelivered && !row.isFailed) store.update(row.copy(isFailed = true, isDelivered = false, isRead = false))
        }
    }

    /** The contact row that lists a MeshCore user or channel among the chats; the name follows the radio's. */
    suspend fun ensureContact(key: String, name: String) {
        val alias = name.ifBlank { MeshCoreThread.displayName(key) }
        val existing = store.contactAlias(key)
        if (existing == null) store.insertContact(key, alias)
        else if (existing != alias && !alias.startsWith("MeshCore ")) store.renameContact(key, alias)
    }
}
