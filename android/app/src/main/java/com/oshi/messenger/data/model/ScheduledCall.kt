package com.oshi.messenger.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Scheduled Call - Compatible with iOS ScheduledCallManager
 */
@Entity(tableName = "scheduled_calls")
data class ScheduledCall(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val peerPublicKey: String,
    val peerName: String,
    val scheduledTime: Long,
    val isVideoCall: Boolean = false,
    val title: String = "",
    val notes: String = "",
    val reminderMinutes: Int = 15,  // Reminder before call
    val isCreatedByMe: Boolean = true,
    val status: ScheduledCallStatus = ScheduledCallStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val notificationSent: Boolean = false
)

enum class ScheduledCallStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    COMPLETED,
    MISSED,
    CANCELLED
}

/**
 * Scheduled call notification data
 */
data class ScheduledCallNotification(
    val callId: String,
    val peerName: String,
    val scheduledTime: Long,
    val isVideoCall: Boolean,
    val minutesUntilCall: Int
)
