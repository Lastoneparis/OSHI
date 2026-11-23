package com.oshi.messenger.network.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log

/**
 * Broadcast receiver for WiFi Direct state changes
 * Used for Android-to-Android P2P connections
 */
class WifiDirectReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WifiDirectReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                Log.d(TAG, "WiFi P2P state changed: enabled=$isEnabled")
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.d(TAG, "WiFi P2P peers changed")
                // MeshNetworkManager handles peer discovery
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                Log.d(TAG, "WiFi P2P connection changed")
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                Log.d(TAG, "This device WiFi P2P info changed")
            }
        }
    }
}
