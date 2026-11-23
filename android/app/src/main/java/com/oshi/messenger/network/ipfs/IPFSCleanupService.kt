package com.oshi.messenger.network.ipfs

import android.content.Context
import android.util.Log
import com.oshi.messenger.BuildConfig
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IPFSCleanupService - Manages IPFS/Pinata message lifecycle
 *
 * Compatible with iOS PinataCleanupService and IPFSEphemeralManager:
 * - Delete messages immediately once received
 * - Auto-delete after 30 days if not delivered
 * - Uses VPS proxy for unpin requests (hides user IP)
 *
 * Flow:
 * 1. Message uploaded to IPFS → tracked in pendingMessages
 * 2. Recipient downloads → triggers unpin via VPS
 * 3. If not delivered after 30 days → auto-cleanup
 */
@Singleton
class IPFSCleanupService @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "IPFSCleanup"
        private const val PREFS_NAME = "ipfs_cleanup_prefs"
        private const val SENT_MESSAGES_KEY = "sent_ipfs_messages"
        private const val RECEIVED_MESSAGES_KEY = "received_ipfs_messages"

        // Same as iOS
        private const val RETENTION_DAYS = 30
        private const val CLEANUP_INTERVAL_HOURS = 24L

        // VPS endpoints (same as iOS)
        private val VPS_URL = BuildConfig.VPS_URL
        private const val PINATA_PROXY_ENDPOINT = "/api/pinata/pinning/unpin/"
        private const val DELIVERY_RECEIPT_ENDPOINT = "/api/delivery-receipt/"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // In-memory cache
    private var sentMessages: MutableList<SentIPFSMessage> = mutableListOf()
    private var receivedMessages: MutableList<ReceivedIPFSMessage> = mutableListOf()

    init {
        loadMessages()
        startBackgroundCleanup()
        Log.d(TAG, "🔐 IPFSCleanupService initialized")
        Log.d(TAG, "   Pending sent: ${sentMessages.size}")
        Log.d(TAG, "   Pending received: ${receivedMessages.size}")
        Log.d(TAG, "   Retention: $RETENTION_DAYS days")
    }

    // ============================================================================
    // TRACK SENT MESSAGES
    // ============================================================================

    /**
     * Track a message that was uploaded to IPFS
     * Call this after uploading via Pinata
     */
    fun trackSentMessage(
        ipfsHash: String,
        messageId: String,
        recipientPublicKey: String,
        isGroupMessage: Boolean = false
    ) {
        // Avoid duplicates
        if (sentMessages.any { it.ipfsHash == ipfsHash }) {
            return
        }

        val message = SentIPFSMessage(
            ipfsHash = ipfsHash,
            messageId = messageId,
            recipientPublicKey = recipientPublicKey,
            sentAt = System.currentTimeMillis(),
            isDelivered = false,
            deliveredAt = null,
            isGroupMessage = isGroupMessage
        )

        sentMessages.add(message)
        saveSentMessages()

        Log.d(TAG, "📝 Tracking sent message: ${ipfsHash.take(12)}...")
    }

    // ============================================================================
    // TRACK RECEIVED MESSAGES - IMMEDIATELY DELETE
    // ============================================================================

    /**
     * Track a message that was received from IPFS
     * This IMMEDIATELY triggers deletion from Pinata
     */
    fun trackReceivedMessage(ipfsHash: String, senderPublicKey: String) {
        // Avoid duplicates
        if (receivedMessages.any { it.ipfsHash == ipfsHash }) {
            return
        }

        val message = ReceivedIPFSMessage(
            ipfsHash = ipfsHash,
            senderPublicKey = senderPublicKey,
            receivedAt = System.currentTimeMillis(),
            isDeleted = false
        )

        receivedMessages.add(message)
        saveReceivedMessages()

        Log.d(TAG, "📝 Tracking received message: ${ipfsHash.take(12)}...")

        // Immediately trigger deletion (same as iOS)
        scope.launch {
            deleteReceivedMessageFromPinata(ipfsHash)
        }
    }

    // ============================================================================
    // DELETE FROM PINATA
    // ============================================================================

    /**
     * Delete a received message from Pinata via VPS proxy
     */
    private suspend fun deleteReceivedMessageFromPinata(ipfsHash: String) {
        // Notify sender of delivery
        notifySenderOfDelivery(ipfsHash)

        // Unpin from Pinata via VPS
        unpinFromPinata(ipfsHash)

        // Mark as deleted locally
        receivedMessages.find { it.ipfsHash == ipfsHash }?.let {
            val index = receivedMessages.indexOf(it)
            receivedMessages[index] = it.copy(isDeleted = true)
            saveReceivedMessages()
        }
    }

    /**
     * Notify sender that message was delivered (so they can unpin)
     */
    private suspend fun notifySenderOfDelivery(ipfsHash: String) {
        try {
            val url = "$VPS_URL$DELIVERY_RECEIPT_ENDPOINT$ipfsHash"
            val request = Request.Builder()
                .url(url)
                .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                Log.d(TAG, "✅ Notified sender of delivery: ${ipfsHash.take(12)}...")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to notify sender: ${e.message}")
        }
    }

    /**
     * Unpin from Pinata via VPS proxy
     */
    private suspend fun unpinFromPinata(ipfsHash: String) {
        try {
            val url = "$VPS_URL$PINATA_PROXY_ENDPOINT$ipfsHash"
            val request = Request.Builder()
                .url(url)
                .delete()
                .build()

            val response = httpClient.newCall(request).execute()
            when (response.code) {
                200 -> Log.d(TAG, "✅ Unpinned from Pinata: ${ipfsHash.take(12)}...")
                404 -> Log.d(TAG, "ℹ️ Hash not found (already deleted): ${ipfsHash.take(12)}...")
                else -> Log.w(TAG, "⚠️ Unpin returned ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Unpin failed: ${e.message}")
        }
    }

    // ============================================================================
    // MARK AS DELIVERED (SENDER SIDE)
    // ============================================================================

    /**
     * Mark a sent message as delivered and delete from Pinata
     */
    suspend fun markAsDelivered(ipfsHash: String) {
        sentMessages.find { it.ipfsHash == ipfsHash }?.let {
            val index = sentMessages.indexOf(it)
            sentMessages[index] = it.copy(
                isDelivered = true,
                deliveredAt = System.currentTimeMillis()
            )
            saveSentMessages()

            Log.d(TAG, "✅ Marked as delivered: ${ipfsHash.take(12)}...")

            // Delete from Pinata
            unpinFromPinata(ipfsHash)
        }
    }

    fun markAsDeliveredByMessageId(messageId: String) {
        sentMessages.find { it.messageId == messageId }?.let {
            scope.launch {
                markAsDelivered(it.ipfsHash)
            }
        }
    }

    // ============================================================================
    // 30-DAY AUTO CLEANUP
    // ============================================================================

    /**
     * Delete messages older than 30 days
     */
    suspend fun cleanupOldMessages() {
        val cutoffTime = System.currentTimeMillis() - (RETENTION_DAYS * 24 * 60 * 60 * 1000L)
        var cleanedCount = 0

        // Cleanup old sent messages
        val oldSentMessages = sentMessages.filter { it.sentAt < cutoffTime }
        for (message in oldSentMessages) {
            Log.d(TAG, "🗑️ Message ${message.ipfsHash.take(12)}... is older than $RETENTION_DAYS days")
            unpinFromPinata(message.ipfsHash)
            cleanedCount++
        }
        sentMessages.removeAll { it.sentAt < cutoffTime }
        saveSentMessages()

        // Cleanup old received messages tracking
        receivedMessages.removeAll { it.receivedAt < cutoffTime }
        saveReceivedMessages()

        if (cleanedCount > 0) {
            Log.d(TAG, "✅ Cleaned up $cleanedCount old messages")
        }
    }

    /**
     * Delete all delivered messages from Pinata
     */
    suspend fun cleanupDeliveredMessages() {
        val deliveredMessages = sentMessages.filter { it.isDelivered }
        for (message in deliveredMessages) {
            unpinFromPinata(message.ipfsHash)
        }
        sentMessages.removeAll { it.isDelivered }
        saveSentMessages()
    }

    /**
     * Delete all pending received messages
     */
    suspend fun cleanupPendingReceivedMessages() {
        val pendingMessages = receivedMessages.filter { !it.isDeleted }
        for (message in pendingMessages) {
            deleteReceivedMessageFromPinata(message.ipfsHash)
        }
    }

    // ============================================================================
    // BACKGROUND CLEANUP
    // ============================================================================

    private fun startBackgroundCleanup() {
        // Run cleanup every 24 hours
        scope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_HOURS * 60 * 60 * 1000L)
                cleanupOldMessages()
                cleanupPendingReceivedMessages()
            }
        }

        // Initial cleanup after 30 seconds
        scope.launch {
            delay(30_000)
            cleanupOldMessages()
            cleanupPendingReceivedMessages()
        }

        // Cleanup received messages after 5 seconds
        scope.launch {
            delay(5_000)
            cleanupPendingReceivedMessages()
        }
    }

    // ============================================================================
    // PERSISTENCE
    // ============================================================================

    private fun loadMessages() {
        try {
            prefs.getString(SENT_MESSAGES_KEY, null)?.let { data ->
                sentMessages = json.decodeFromString<List<SentIPFSMessage>>(data).toMutableList()
            }
            prefs.getString(RECEIVED_MESSAGES_KEY, null)?.let { data ->
                receivedMessages = json.decodeFromString<List<ReceivedIPFSMessage>>(data).toMutableList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load messages: ${e.message}")
        }
    }

    private fun saveSentMessages() {
        try {
            val data = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(SentIPFSMessage.serializer()),
                sentMessages
            )
            prefs.edit().putString(SENT_MESSAGES_KEY, data).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sent messages: ${e.message}")
        }
    }

    private fun saveReceivedMessages() {
        try {
            val data = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ReceivedIPFSMessage.serializer()),
                receivedMessages
            )
            prefs.edit().putString(RECEIVED_MESSAGES_KEY, data).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save received messages: ${e.message}")
        }
    }

    // ============================================================================
    // STATISTICS
    // ============================================================================

    fun getStatistics(): CleanupStatistics {
        val fiveDaysAgo = System.currentTimeMillis() - (25 * 24 * 60 * 60 * 1000L)

        return CleanupStatistics(
            totalTracked = sentMessages.size,
            delivered = sentMessages.count { it.isDelivered },
            pending = sentMessages.count { !it.isDelivered },
            expiringSoon = sentMessages.count { !it.isDelivered && it.sentAt < fiveDaysAgo },
            oldestMessage = sentMessages.minByOrNull { it.sentAt }?.sentAt,
            receivedPendingDeletion = receivedMessages.count { !it.isDeleted }
        )
    }

    fun cleanup() {
        scope.cancel()
    }
}

// ============================================================================
// DATA CLASSES
// ============================================================================

@Serializable
data class SentIPFSMessage(
    val ipfsHash: String,
    val messageId: String,
    val recipientPublicKey: String,
    val sentAt: Long,
    val isDelivered: Boolean,
    val deliveredAt: Long?,
    val isGroupMessage: Boolean
)

@Serializable
data class ReceivedIPFSMessage(
    val ipfsHash: String,
    val senderPublicKey: String,
    val receivedAt: Long,
    val isDeleted: Boolean
)

data class CleanupStatistics(
    val totalTracked: Int,
    val delivered: Int,
    val pending: Int,
    val expiringSoon: Int,
    val oldestMessage: Long?,
    val receivedPendingDeletion: Int
)
