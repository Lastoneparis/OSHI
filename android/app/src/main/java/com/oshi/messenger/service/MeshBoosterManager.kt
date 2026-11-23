package com.oshi.messenger.service

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.oshi.messenger.network.mesh.MeshNetworkManager
import com.oshi.messenger.network.mesh.CrossPlatformMesh
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MeshBoosterManager - Compatible with iOS MeshBoosterManager.swift
 * Enhances mesh network connectivity and provides visualization data
 */
@Singleton
class MeshBoosterManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val meshNetworkManager: MeshNetworkManager,
    private val crossPlatformMesh: CrossPlatformMesh
) {
    companion object {
        private const val TAG = "MeshBoosterManager"
        private const val BOOST_INTERVAL_MS = 5000L
        private const val MAX_RECONNECT_ATTEMPTS = 3
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = context.getSharedPreferences("mesh_booster", Context.MODE_PRIVATE)

    // State
    private val _isBoostActive = MutableStateFlow(false)
    val isBoostActive: StateFlow<Boolean> = _isBoostActive.asStateFlow()

    private val _boostStatus = MutableStateFlow<BoostStatus>(BoostStatus.Idle)
    val boostStatus: StateFlow<BoostStatus> = _boostStatus.asStateFlow()

    private val _networkTopology = MutableStateFlow<NetworkTopology>(NetworkTopology())
    val networkTopology: StateFlow<NetworkTopology> = _networkTopology.asStateFlow()

    private val _signalStrengths = MutableStateFlow<Map<String, Int>>(emptyMap())
    val signalStrengths: StateFlow<Map<String, Int>> = _signalStrengths.asStateFlow()

    private var boostJob: Job? = null

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * Start mesh boosting
     */
    fun startBoost() {
        if (_isBoostActive.value) return

        _isBoostActive.value = true
        _boostStatus.value = BoostStatus.Boosting

        boostJob = scope.launch {
            Log.d(TAG, "Starting mesh boost")

            while (isActive && _isBoostActive.value) {
                try {
                    // Scan for new peers
                    scanForPeers()

                    // Optimize connections
                    optimizeConnections()

                    // Update topology
                    updateNetworkTopology()

                    // Update signal strengths
                    updateSignalStrengths()

                    delay(BOOST_INTERVAL_MS)

                } catch (e: Exception) {
                    Log.e(TAG, "Boost error: ${e.message}")
                }
            }
        }
    }

    /**
     * Stop mesh boosting
     */
    fun stopBoost() {
        _isBoostActive.value = false
        _boostStatus.value = BoostStatus.Idle
        boostJob?.cancel()
        boostJob = null
        Log.d(TAG, "Stopped mesh boost")
    }

    /**
     * Get mesh health score (0-100)
     */
    fun getMeshHealthScore(): Int {
        val topology = _networkTopology.value

        var score = 0

        // Base score for having any peers
        if (topology.totalPeers > 0) score += 20

        // Score for direct connections
        score += minOf(30, topology.directConnections * 10)

        // Score for relay capability
        if (topology.relayNodes > 0) score += 20

        // Score for cross-platform connectivity
        if (topology.androidPeers > 0 && topology.iosPeers > 0) score += 15

        // Score for average signal strength
        val avgSignal = _signalStrengths.value.values.average().takeIf { !it.isNaN() } ?: 0.0
        score += (avgSignal / 100 * 15).toInt()

        return minOf(100, score)
    }

    /**
     * Get network statistics
     */
    fun getNetworkStats(): NetworkStats {
        val topology = _networkTopology.value
        val health = getMeshHealthScore()

        return NetworkStats(
            healthScore = health,
            totalPeers = topology.totalPeers,
            directConnections = topology.directConnections,
            relayConnections = topology.relayConnections,
            androidPeers = topology.androidPeers,
            iosPeers = topology.iosPeers,
            averageLatency = topology.averageLatency,
            messagesSent = getMessagesSent(),
            messagesReceived = getMessagesReceived()
        )
    }

    /**
     * Force reconnect to all peers
     */
    fun forceReconnect() {
        scope.launch {
            _boostStatus.value = BoostStatus.Reconnecting

            try {
                // Restart mesh services
                meshNetworkManager.stop()
                crossPlatformMesh.stop()

                delay(1000)

                meshNetworkManager.start(
                    cryptoManager.getPublicKeyString(),
                    getDisplayName()
                )
                crossPlatformMesh.start(
                    cryptoManager.getPublicKeyString(),
                    getDisplayName()
                )

                _boostStatus.value = if (_isBoostActive.value) BoostStatus.Boosting else BoostStatus.Idle

            } catch (e: Exception) {
                Log.e(TAG, "Reconnect failed: ${e.message}")
                _boostStatus.value = BoostStatus.Error(e.message ?: "Reconnect failed")
            }
        }
    }

    /**
     * Get peers visualization data
     */
    fun getPeersVisualization(): List<PeerVisualization> {
        val result = mutableListOf<PeerVisualization>()

        // Add mesh peers
        meshNetworkManager.connectedPeers.value.forEach { peer ->
            result.add(PeerVisualization(
                id = peer.publicKey,
                name = peer.displayName,
                platform = "android",
                connectionType = ConnectionType.MESH_DIRECT,
                signalStrength = _signalStrengths.value[peer.publicKey] ?: 50,
                distance = calculateDistance(peer.publicKey)
            ))
        }

        // Add cross-platform peers
        crossPlatformMesh.connectedPeers.value.forEach { peer ->
            result.add(PeerVisualization(
                id = peer.publicKey,
                name = peer.displayName,
                platform = peer.platform,
                connectionType = ConnectionType.CROSS_PLATFORM,
                signalStrength = _signalStrengths.value[peer.publicKey] ?: 50,
                distance = calculateDistance(peer.publicKey)
            ))
        }

        return result
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    private lateinit var cryptoManager: com.oshi.messenger.network.encryption.CryptoManager

    @Inject
    fun setCryptoManager(manager: com.oshi.messenger.network.encryption.CryptoManager) {
        cryptoManager = manager
    }

    private fun getDisplayName(): String {
        return prefs.getString("display_name", "User") ?: "User"
    }

    private suspend fun scanForPeers() {
        // Mesh manager handles scanning automatically
        // This method can trigger additional discovery mechanisms
        Log.d(TAG, "Scanning for peers...")
    }

    private suspend fun optimizeConnections() {
        val weakConnections = _signalStrengths.value.filter { it.value < 30 }

        weakConnections.forEach { (peerId, _) ->
            // Try to improve weak connections
            Log.d(TAG, "Attempting to optimize connection to $peerId")
        }
    }

    private fun updateNetworkTopology() {
        val meshPeers = meshNetworkManager.connectedPeers.value
        val crossPeers = crossPlatformMesh.connectedPeers.value

        val topology = NetworkTopology(
            totalPeers = meshPeers.size + crossPeers.size,
            directConnections = meshPeers.size,
            relayConnections = 0,  // Would be calculated from routing table
            relayNodes = 0,
            androidPeers = meshPeers.size + crossPeers.count { it.platform == "android" },
            iosPeers = crossPeers.count { it.platform == "ios" },
            averageLatency = 50  // Would be measured
        )

        _networkTopology.value = topology
    }

    private fun updateSignalStrengths() {
        val strengths = mutableMapOf<String, Int>()

        // Estimate signal strength based on last activity
        meshNetworkManager.connectedPeers.value.forEach { peer ->
            strengths[peer.publicKey] = estimateSignalStrength(peer.publicKey)
        }

        crossPlatformMesh.connectedPeers.value.forEach { peer ->
            strengths[peer.publicKey] = estimateSignalStrength(peer.publicKey)
        }

        _signalStrengths.value = strengths
    }

    private fun estimateSignalStrength(peerId: String): Int {
        // This would use actual RSSI values from Bluetooth/WiFi
        // For now, return a simulated value
        return (50..90).random()
    }

    private fun calculateDistance(peerId: String): Float {
        // Would calculate based on signal strength
        val signal = _signalStrengths.value[peerId] ?: 50
        return (100 - signal) * 2f  // Rough estimate in meters
    }

    private fun getMessagesSent(): Long {
        return prefs.getLong("messages_sent", 0)
    }

    private fun getMessagesReceived(): Long {
        return prefs.getLong("messages_received", 0)
    }

    fun incrementMessagesSent() {
        val current = getMessagesSent()
        prefs.edit().putLong("messages_sent", current + 1).apply()
    }

    fun incrementMessagesReceived() {
        val current = getMessagesReceived()
        prefs.edit().putLong("messages_received", current + 1).apply()
    }

    fun cleanup() {
        stopBoost()
        scope.cancel()
    }
}

/**
 * Boost status
 */
sealed class BoostStatus {
    object Idle : BoostStatus()
    object Boosting : BoostStatus()
    object Reconnecting : BoostStatus()
    data class Error(val message: String) : BoostStatus()
}

/**
 * Network topology data
 */
data class NetworkTopology(
    val totalPeers: Int = 0,
    val directConnections: Int = 0,
    val relayConnections: Int = 0,
    val relayNodes: Int = 0,
    val androidPeers: Int = 0,
    val iosPeers: Int = 0,
    val averageLatency: Int = 0
)

/**
 * Network statistics
 */
data class NetworkStats(
    val healthScore: Int,
    val totalPeers: Int,
    val directConnections: Int,
    val relayConnections: Int,
    val androidPeers: Int,
    val iosPeers: Int,
    val averageLatency: Int,
    val messagesSent: Long,
    val messagesReceived: Long
)

/**
 * Peer visualization data for UI
 */
data class PeerVisualization(
    val id: String,
    val name: String,
    val platform: String,
    val connectionType: ConnectionType,
    val signalStrength: Int,  // 0-100
    val distance: Float       // Estimated distance in meters
)

// ConnectionType is defined in CallManager.kt
// MESH_DIRECT, CROSS_PLATFORM, RELAY, etc.
