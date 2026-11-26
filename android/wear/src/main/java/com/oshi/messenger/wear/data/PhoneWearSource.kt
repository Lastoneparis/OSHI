package com.oshi.messenger.wear.data

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.oshi.wearbridge.WearConvKind
import com.oshi.wearbridge.WearConversation
import com.oshi.wearbridge.WearOp
import com.oshi.wearbridge.WearProtocol
import com.oshi.wearbridge.WearRequest
import com.oshi.wearbridge.WearResponse
import com.oshi.wearbridge.WearStatus
import com.oshi.wearbridge.WearVoiceFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * __WEAR_BRIDGE_2026_11_17__ Asks the phone, over the Wearable Data Layer.
 *
 * One request = one MessageClient message to the node advertising
 * [WearProtocol.CAPABILITY_PHONE]; the answer comes back on
 * [WearProtocol.PATH_RESPONSE] and is matched by its request id. Everything
 * stays in memory; nothing is written anywhere.
 */
class PhoneWearSource(context: Context) : WearSource {

    private val app = context.applicationContext
    private val messages = Wearable.getMessageClient(app)
    private val capabilities = Wearable.getCapabilityClient(app)
    private val channels = Wearable.getChannelClient(app)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<WearResponse>>()
    @Volatile private var listening = false

    private val listener = MessageClient.OnMessageReceivedListener { event ->
        if (event.path != WearProtocol.PATH_RESPONSE) return@OnMessageReceivedListener
        val resp = WearResponse.decode(event.data) ?: return@OnMessageReceivedListener
        pending.remove(resp.rid)?.complete(resp)
    }

    private suspend fun ensureListening() {
        if (listening) return
        messages.addListener(listener).await()
        listening = true
    }

    private suspend fun phoneNodeId(): String? = runCatching {
        val nodes = capabilities
            .getCapability(WearProtocol.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
            .await().nodes
        (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
    }.getOrNull()

    private suspend fun ask(
        req: WearRequest,
        timeoutMs: Long = 8_000,
        deliver: (suspend (node: String) -> Unit)? = null,
    ): Outcome<WearResponse> {
        return try {
            ensureListening()
            val node = phoneNodeId() ?: return Outcome.Fail(Reason.NO_PHONE)
            val waiter = CompletableDeferred<WearResponse>()
            pending[req.rid] = waiter
            if (deliver != null) deliver(node)
            else messages.sendMessage(node, WearProtocol.PATH_REQUEST, req.encode()).await()
            val resp = withTimeoutOrNull(timeoutMs) { waiter.await() }
                ?: return Outcome.Fail(Reason.NO_PHONE)
            if (resp.status == WearStatus.OK) Outcome.Ok(resp) else Outcome.Fail(Reason.of(resp.status))
        } catch (e: Exception) {
            Log.w(TAG, "request ${req.op} failed: ${e.javaClass.simpleName}")
            Outcome.Fail(Reason.NO_PHONE)
        } finally {
            pending.remove(req.rid)
        }
    }

    private fun rid() = UUID.randomUUID().toString()

    override suspend fun inbox(): Outcome<WearInbox> =
        when (val r = ask(WearRequest(rid(), WearOp.LIST, limit = WearProtocol.MAX_CONVERSATIONS))) {
            is Outcome.Ok -> Outcome.Ok(WearInbox(r.value.conversations, r.value.caps.toSet(), r.value.net, r.value.strict))
            is Outcome.Fail -> r
        }

    override suspend fun thread(kind: WearConvKind, id: String): Outcome<WearThread> =
        when (val r = ask(WearRequest(rid(), WearOp.THREAD, kind, id, limit = WearProtocol.MAX_THREAD_MESSAGES))) {
            is Outcome.Ok -> Outcome.Ok(WearThread(r.value.title.orEmpty(), r.value.messages))
            is Outcome.Fail -> r
        }

    override suspend fun send(kind: WearConvKind, id: String, text: String): Outcome<Unit> =
        when (val r = ask(WearRequest(rid(), WearOp.SEND, kind, id, text = text), timeoutMs = 15_000)) {
            is Outcome.Ok -> Outcome.Ok(Unit)
            is Outcome.Fail -> r
        }

    override suspend fun react(kind: WearConvKind, id: String, messageId: String, emoji: String): Outcome<Unit> =
        when (val r = ask(WearRequest(rid(), WearOp.REACT, kind, id, messageId = messageId, emoji = emoji))) {
            is Outcome.Ok -> Outcome.Ok(Unit)
            is Outcome.Fail -> r
        }

    override suspend fun markRead(kind: WearConvKind, id: String): Outcome<Unit> =
        when (val r = ask(WearRequest(rid(), WearOp.READ, kind, id))) {
            is Outcome.Ok -> Outcome.Ok(Unit)
            is Outcome.Fail -> r
        }

    override suspend fun thumbnail(kind: WearConvKind, id: String, messageId: String): Outcome<ByteArray> =
        when (val r = ask(WearRequest(rid(), WearOp.THUMB, kind, id, messageId = messageId), timeoutMs = 10_000)) {
            is Outcome.Ok -> r.value.thumb?.let { Outcome.Ok(it) } ?: Outcome.Fail(Reason.NOT_FOUND)
            is Outcome.Fail -> r
        }

    /**
     * __WEAR_POLISH_2026_11_25__ The recording goes over a ChannelClient
     * stream (a 60 s note is bigger than a MessageClient message may be); the
     * phone answers on the usual response path once it has handed the note
     * to its own voice-message send.
     */
    override suspend fun sendVoice(kind: WearConvKind, id: String, audio: ByteArray, durationMs: Long): Outcome<Unit> {
        val req = WearRequest(rid(), WearOp.VOICE, kind, id, durationMs = durationMs)
        if (!req.isWellFormed() || audio.size > WearProtocol.MAX_VOICE_BYTES) return Outcome.Fail(Reason.INVALID)
        val frame = WearVoiceFrame.encode(req, audio)
        return when (val r = ask(req, timeoutMs = 25_000) { node ->
            val channel = channels.openChannel(node, WearProtocol.PATH_VOICE).await()
            try {
                // Closing the stream is the end-of-note marker; the PHONE closes the
                // channel once it has read it (closing here could cut the tail).
                channels.getOutputStream(channel).await().use { it.write(frame); it.flush() }
            } catch (e: Exception) {
                runCatching { channels.close(channel) }
                throw e
            }
        }) {
            is Outcome.Ok -> Outcome.Ok(Unit)
            is Outcome.Fail -> r
        }
    }

    override fun close() {
        if (listening) runCatching { messages.removeListener(listener) }
        listening = false
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    private companion object { const val TAG = "OshiWearSource" }
}
