package com.oshi.messenger.service.wear

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.oshi.wearbridge.WearProtocol
import com.oshi.wearbridge.WearRequest
import com.oshi.wearbridge.WearResponse
import com.oshi.wearbridge.WearStatus
import com.oshi.wearbridge.WearVoiceFrame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * __WEAR_BRIDGE_2026_11_17__ Answers the OSHI watch app.
 *
 * Bound by Play services when a paired watch sends a message on
 * [WearProtocol.PATH_REQUEST] (manifest intent-filter, path-prefix filtered).
 * Play services delivers Data Layer messages only between apps with the same
 * package name AND the same signing certificate, so nothing but our own watch
 * app can reach this.
 *
 * When Settings → Smartwatch is off (the default) every request is answered
 * [WearStatus.DISABLED] before anything else is resolved — no repository, no
 * database, no Hilt graph lookup — see [WearBridgeHandler].
 */
class WearBridgeService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearProtocol.PATH_REQUEST) return
        val response: WearResponse? = runBlocking {
            withTimeoutOrNull(15_000) {
                // Gate on the switch BEFORE building the backend, so an off bridge
                // never even instantiates the repository graph.
                if (!PhoneWearBackend.isEnabled(applicationContext)) {
                    WearRequest.decode(event.data)?.let { WearResponse.status(it.rid, WearStatus.DISABLED) }
                } else {
                    WearBridgeHandler(PhoneWearBackend.create(applicationContext)).handle(event.data)
                }
            }
        }
        reply(event.sourceNodeId, response)
    }

    /**
     * __WEAR_POLISH_2026_11_25__ A voice message from the watch. The stream is
     * read with hard caps ([WearVoiceFrame.read]) into memory only — never a
     * file — then handed to the same gated handler; the answer goes back on
     * [WearProtocol.PATH_RESPONSE] like every other request.
     */
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WearProtocol.PATH_VOICE) return
        val client = Wearable.getChannelClient(this)
        val frame = runCatching {
            Tasks.await(client.getInputStream(channel), 5, TimeUnit.SECONDS).use { WearVoiceFrame.read(it) }
        }.getOrNull()
        runCatching { client.close(channel) }
        val response: WearResponse? = runBlocking {
            withTimeoutOrNull(20_000) {
                val rid = frame?.first?.rid
                if (!PhoneWearBackend.isEnabled(applicationContext)) {
                    rid?.let { WearResponse.status(it, WearStatus.DISABLED) }
                } else {
                    WearBridgeHandler(PhoneWearBackend.create(applicationContext)).handleVoice(frame)
                }
            }
        }
        reply(channel.nodeId, response)
    }

    private fun reply(nodeId: String, response: WearResponse?) {
        if (response == null) {
            Log.w(TAG, "wear request dropped (undecodable or timed out)")
            return
        }
        runCatching {
            Tasks.await(
                Wearable.getMessageClient(this).sendMessage(nodeId, WearProtocol.PATH_RESPONSE, response.encode()),
                5, TimeUnit.SECONDS,
            )
        }.onFailure { Log.w(TAG, "wear response not delivered: ${it.message}") }
    }

    private companion object { const val TAG = "WearBridge" }
}
