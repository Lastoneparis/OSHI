package com.oshi.messenger.service.wear

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.oshi.wearbridge.WearProtocol
import com.oshi.wearbridge.WearRequest
import com.oshi.wearbridge.WearResponse
import com.oshi.wearbridge.WearStatus
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
        if (response == null) {
            Log.w(TAG, "wear request dropped (undecodable or timed out)")
            return
        }
        runCatching {
            Tasks.await(
                Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, WearProtocol.PATH_RESPONSE, response.encode()),
                5, TimeUnit.SECONDS,
            )
        }.onFailure { Log.w(TAG, "wear response not delivered: ${it.message}") }
    }

    private companion object { const val TAG = "WearBridge" }
}
