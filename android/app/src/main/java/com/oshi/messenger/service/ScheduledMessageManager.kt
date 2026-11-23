package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// =========================================================================
// Domain models (task 3.2.1)
// =========================================================================

enum class MessageTone(val displayName: String, val emoji: String) {
    ROMANTIC("Romantic", "\u2764\uFE0F"),
    FRIENDLY("Friendly", "\uD83D\uDE0A"),
    FAMILY("Family", "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"),
    CASUAL("Casual", "\uD83D\uDC4B"),
    FORMAL("Formal", "\uD83E\uDD1D");

    /**
     * The writing style [com.oshi.messenger.service.NLPManager.generateThinkingOfYou]
     * should compose in for this relationship tone.
     *
     * Two enums exist because they answer different questions: [MessageTone] is the
     * 1:1 port of iOS's `ScheduledMessage.RelationshipTone`
     * (`OSHI/ScheduledMessageManager.swift:30`) and is what gets PERSISTED with the
     * scheduled message, while `ImprovementStyle` is the writing-assistant's
     * 9-value palette. This function is the bridge, and it is a `when` over a
     * closed enum on purpose: adding a tone will not compile until someone decides
     * how it should sound.
     *
     * The targets are chosen against what the generator actually branches on
     * (`NLPManager.thinkingOfYouTemplate`: `isRomantic` = SEDUCTIVE|FLIRTY,
     * `isPlayful` = PLAYFUL|CASUAL, `isFormal` = FORMAL|PROFESSIONAL, else the warm
     * default) and against iOS's own tone descriptions
     * (`NLPManager.swift:1729-1735`):
     *
     * | tone     | iOS description        | style      | template branch |
     * |----------|------------------------|------------|-----------------|
     * | ROMANTIC | "romantic and loving"  | SEDUCTIVE  | isRomantic      |
     * | FRIENDLY | "warm and friendly"    | FRIENDLY   | default (warm)  |
     * | FAMILY   | "caring and familial"  | INTRIGUED  | default (warm)  |
     * | CASUAL   | "casual and relaxed"   | CASUAL     | isPlayful       |
     * | FORMAL   | "formal and respectful"| FORMAL     | isFormal        |
     *
     * FAMILY maps to INTRIGUED ("curious, attentive, caring") rather than to
     * FRIENDLY so the two do not produce the identical prompt \u2014 the templated
     * fallback still lands on the same warm branch for both, which is honest:
     * Android has no separate family template and inventing one here would be a
     * different change.
     */
    fun writingStyle(): NLPManager.ImprovementStyle = when (this) {
        ROMANTIC -> NLPManager.ImprovementStyle.SEDUCTIVE
        FRIENDLY -> NLPManager.ImprovementStyle.FRIENDLY
        FAMILY -> NLPManager.ImprovementStyle.INTRIGUED
        CASUAL -> NLPManager.ImprovementStyle.CASUAL
        FORMAL -> NLPManager.ImprovementStyle.FORMAL
    }
}

enum class ScheduledMessageStatus { PENDING, SENT, CANCELLED, FAILED }

data class ScheduledMessage(
    val id: String = UUID.randomUUID().toString(),
    val recipientPublicKey: String,
    val content: String,
    val scheduledTime: Long,
    val tone: MessageTone = MessageTone.CASUAL,
    val status: ScheduledMessageStatus = ScheduledMessageStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val isAiGenerated: Boolean = false,
    /**
     * When true, recipientPublicKey is a groupId and the message will be
     * dispatched via GroupManager.sendGroupMessage instead of
     * MessageRepository.sendTextMessage. Defaults to false for backward
     * compatibility with existing persisted entries.
     */
    val isGroup: Boolean = false
)

// Legacy type aliases (top-level, as Kotlin does not support nested typealias)
@Deprecated("Use MessageTone", ReplaceWith("MessageTone"))
typealias RelationshipTone = MessageTone
@Deprecated("Use ScheduledMessageStatus", ReplaceWith("ScheduledMessageStatus"))
typealias ScheduleStatus = ScheduledMessageStatus

/**
 * ScheduledMessageManager — task 3.2.1 - 3.2.6
 *
 * Stores scheduled messages in a JSON file on internal storage and
 * delivers them via WorkManager OneTimeWorkRequest with an initial delay.
 *
 * Compatible with iOS ScheduledMessageManager.swift pattern.
 */
@Singleton
class ScheduledMessageManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ScheduledMessageManager"
        private const val STORAGE_FILE = "scheduled_messages.json"

        /** [com.oshi.messenger.data.local.atrest.SealedFile] AAD label (at-rest sealing, 2026-09-22). */
        private const val SEAL_LABEL = "scheduled_messages"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()

    private val _scheduledMessages = MutableStateFlow<List<ScheduledMessage>>(emptyList())
    val scheduledMessages: StateFlow<List<ScheduledMessage>> = _scheduledMessages.asStateFlow()

    // Monitor job: re-checks overdue messages every 30 s as a safety net
    private var monitorJob: Job? = null

    init {
        loadMessages()
        startMonitor()
    }

    // =========================================================================
    // PUBLIC API (task 3.2.2 - 3.2.5)
    // =========================================================================

    /**
     * Schedule a new message for future delivery.
     * Persists to storage and enqueues a WorkManager job.
     */
    fun scheduleMessage(
        recipientKey: String,
        content: String,
        scheduledTimeMs: Long,
        tone: MessageTone = MessageTone.CASUAL,
        isAiGenerated: Boolean = false,
        isGroup: Boolean = false
    ): ScheduledMessage {
        val message = ScheduledMessage(
            recipientPublicKey = recipientKey,
            content = content,
            scheduledTime = scheduledTimeMs,
            tone = tone,
            isAiGenerated = isAiGenerated,
            isGroup = isGroup
        )
        val current = _scheduledMessages.value.toMutableList()
        current.add(message)
        _scheduledMessages.value = current
        saveMessages()
        scheduleDeliveryWork(message)
        Log.d(TAG, "Scheduled message ${message.id} for ${recipientKey.take(8)}... at $scheduledTimeMs")
        return message
    }

    /**
     * Cancel a pending scheduled message.
     * Cancels its WorkManager job and marks it CANCELLED in storage.
     */
    fun cancelMessage(messageId: String) {
        val current = _scheduledMessages.value.toMutableList()
        val index = current.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            current[index] = current[index].copy(status = ScheduledMessageStatus.CANCELLED)
            _scheduledMessages.value = current
            saveMessages()
            WorkManager.getInstance(context).cancelUniqueWork("scheduled_msg_$messageId")
            Log.d(TAG, "Cancelled scheduled message $messageId")
        }
    }

    /**
     * Returns a Flow of all scheduled messages (pending, sent, cancelled, failed).
     * Callers can filter by status as needed.
     */
    fun getScheduledMessages(): Flow<List<ScheduledMessage>> = _scheduledMessages.asStateFlow()

    // 🔧 REMOVED (2026-08-24 three-gaps): `generateAiSuggestion(tone)`.
    //
    // It returned one of **25 hardcoded English sentences** picked with
    // `.random()` — shown to users in all 34 locales — and it was the only thing
    // the Scheduled Messages screen's "AI" button called, while the genuine
    // multilingual generator (`NLPManager.generateThinkingOfYou`: Gemini Nano →
    // local LLM → a template keyed by the DETECTED language) sat unused two files
    // away with a single consumer that only pasted into the composer.
    //
    // Both surfaces now go through the real generator:
    //   * chat "+" menu  → `ui/components/ThinkingOfYouSheet.kt`
    //   * settings sheet → `ScheduledMessageViewModel.generateThinkingOfYou`
    // Deleted rather than deprecated so it cannot quietly acquire a third caller.

    // =========================================================================
    // LEGACY / CONVENIENCE API (kept for backward compatibility)
    // =========================================================================

    /** Legacy convenience wrapper — delegates to scheduleMessage */
    fun schedule(
        conversationId: String,
        content: String,
        scheduledTime: Long,
        tone: MessageTone = MessageTone.CASUAL
    ): ScheduledMessage = scheduleMessage(conversationId, content, scheduledTime, tone)

    /** Legacy convenience wrapper — delegates to cancelMessage */
    fun cancel(messageId: String) = cancelMessage(messageId)

    /** Edit the content of a PENDING scheduled message */
    fun editContent(messageId: String, newContent: String) {
        val current = _scheduledMessages.value.toMutableList()
        val index = current.indexOfFirst { it.id == messageId }
        if (index >= 0 && current[index].status == ScheduledMessageStatus.PENDING) {
            current[index] = current[index].copy(content = newContent)
            _scheduledMessages.value = current
            saveMessages()
            Log.d(TAG, "Edited content for scheduled message $messageId")
        }
    }

    /** Pending messages for a specific conversation */
    fun getPendingForConversation(recipientPublicKey: String): List<ScheduledMessage> =
        _scheduledMessages.value.filter {
            it.recipientPublicKey == recipientPublicKey && it.status == ScheduledMessageStatus.PENDING
        }

    // =========================================================================
    // DELIVERY LIFECYCLE (called by ScheduledMessageWorker)
    // =========================================================================

    fun markAsSent(messageId: String) {
        updateStatus(messageId, ScheduledMessageStatus.SENT)
    }

    fun markAsFailed(messageId: String) {
        updateStatus(messageId, ScheduledMessageStatus.FAILED)
    }

    /** Returns the content to send for a given message id */
    fun getContentToSend(messageId: String): String? =
        _scheduledMessages.value.find { it.id == messageId }?.content

    /** Returns the recipient public key for a given message id */
    fun getRecipient(messageId: String): String? =
        _scheduledMessages.value.find { it.id == messageId }?.recipientPublicKey

    /** Returns true if the scheduled message targets a group rather than a direct chat. */
    fun isGroup(messageId: String): Boolean =
        _scheduledMessages.value.find { it.id == messageId }?.isGroup == true

    // =========================================================================
    // INTERNAL HELPERS
    // =========================================================================

    private fun scheduleDeliveryWork(message: ScheduledMessage) {
        val delay = message.scheduledTime - System.currentTimeMillis()
        if (delay <= 0) {
            Log.w(TAG, "Message ${message.id} scheduled in the past — delivering immediately")
        }

        val data = Data.Builder()
            .putString("message_id", message.id)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<ScheduledMessageWorker>()
            .setInitialDelay(maxOf(delay, 0L), TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag("scheduled_message")
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "scheduled_msg_${message.id}",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
    }

    private fun updateStatus(messageId: String, status: ScheduledMessageStatus) {
        val current = _scheduledMessages.value.toMutableList()
        val index = current.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            current[index] = current[index].copy(status = status)
            _scheduledMessages.value = current
            saveMessages()
        }
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                checkOverdueMessages()
                delay(30_000L)
            }
        }
    }

    private fun checkOverdueMessages() {
        val now = System.currentTimeMillis()
        val overdue = _scheduledMessages.value.filter {
            it.status == ScheduledMessageStatus.PENDING && it.scheduledTime <= now
        }
        for (msg in overdue) {
            Log.w(TAG, "Overdue scheduled message found: ${msg.id} — re-scheduling delivery")
            scheduleDeliveryWork(msg.copy(scheduledTime = now + 1_000L))
        }
    }

    /**
     * 🔴 A TORN WRITE HERE DELETED EVERY SCHEDULED MESSAGE, SILENTLY.
     *
     * `file.writeText` truncates the file and then streams the new JSON into it, so
     * a process death anywhere in between — Doze killing the app, a low-memory kill,
     * a crash on another thread — left half a JSON document on disk. [loadMessages]
     * then failed `gson.fromJson`, swallowed the exception, and left
     * `_scheduledMessages` EMPTY; the very next save wrote that emptiness back as
     * the authoritative file. The user's scheduled messages were gone and nothing
     * ever said so.
     *
     * Write to a sibling `.tmp` and rename: a rename within one filesystem is
     * atomic, so a reader sees either the whole previous document or the whole new
     * one, never a fragment. Same shape as [PushRetryQueue.writeToDisk], which
     * already got this right.
     */
    private fun saveMessages() {
        try {
            // At rest (2026-09-22): sealed as OSHISEA1 under the Keystore-wrapped
            // AtRestKey — recipients, contents and times of future messages. Same
            // temp → fsync → atomic rename as before. Without a key it refuses to
            // overwrite a sealed file it could not read (throws → logged below).
            val file = File(context.filesDir, STORAGE_FILE)
            com.oshi.messenger.data.local.atrest.SealedFile.writeAtomic(
                file, SEAL_LABEL, com.oshi.messenger.data.local.atrest.AtRestKey,
                gson.toJson(_scheduledMessages.value).toByteArray(Charsets.UTF_8),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save scheduled messages: ${e.message}")
        }
    }

    private fun loadMessages() {
        try {
            val file = File(context.filesDir, STORAGE_FILE)
            if (file.exists()) {
                val sealedFile = com.oshi.messenger.data.local.atrest.SealedFile
                val keys = com.oshi.messenger.data.local.atrest.AtRestKey
                val legacy = sealedFile.isLegacyPlaintext(file)
                val text = try {
                    sealedFile.readText(file, SEAL_LABEL, keys) ?: return
                } catch (e: java.io.IOException) {
                    // Sealed but not openable. With no key, saveMessages() refuses to
                    // overwrite it, so it survives for a later run that has the key.
                    // With a key that fails to authenticate it (tampered, or the key
                    // store was reset), keep the bytes aside rather than letting the
                    // next save replace them — never delete user data on a read error.
                    if (keys.masterKey() != null) {
                        val aside = File(context.filesDir, "$STORAGE_FILE.unreadable-${System.currentTimeMillis()}")
                        if (file.renameTo(aside)) Log.e(TAG, "Scheduled messages unreadable; kept as ${aside.name}")
                    }
                    Log.e(TAG, "Scheduled messages could not be opened: ${e.message}")
                    return
                }
                val type = object : TypeToken<List<ScheduledMessage>>() {}.type
                // `fromJson` returns null for an empty or whitespace-only file — the
                // exact residue an interrupted write used to leave — and assigning
                // that to a non-null StateFlow throws.
                val messages: List<ScheduledMessage>? = gson.fromJson(text, type)
                if (messages != null) {
                    _scheduledMessages.value = messages
                    Log.d(TAG, "Loaded ${messages.size} scheduled messages")
                    // Legacy plaintext file from an older build: seal it in place
                    // (verified, atomic, no-op without a key; source kept on failure).
                    if (legacy) sealedFile.migrate(file, SEAL_LABEL, keys)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load scheduled messages: ${e.message}")
        }
    }

    fun cleanup() {
        monitorJob?.cancel()
        scope.cancel()
    }
}

// =========================================================================
// WorkManager Worker (task 3.2.6)
// =========================================================================

/**
 * ScheduledMessageWorker — delivers a scheduled message via MessageRepository.
 *
 * WorkManager provides reliable delivery even after process restarts and Doze mode.
 * MessageRepository is retrieved from the application-level Hilt component via
 * EntryPointAccessors to avoid requiring HiltWorker setup.
 */
class ScheduledMessageWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val messageId = inputData.getString("message_id") ?: return Result.failure()
        Log.d(TAG, "Delivering scheduled message: $messageId")

        return try {
            // Access ScheduledMessageManager from the Hilt entry point
            val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
                context.applicationContext,
                ScheduledMessageWorkerEntryPoint::class.java
            )
            val manager = entryPoint.scheduledMessageManager()
            val repository = entryPoint.messageRepository()
            val groupManager = entryPoint.groupManager()

            val content = manager.getContentToSend(messageId)
            val recipient = manager.getRecipient(messageId)
            val isGroup = manager.isGroup(messageId)

            if (content == null || recipient == null) {
                Log.w(TAG, "Message $messageId not found or already processed")
                return Result.failure()
            }

            if (isGroup) {
                // Group dispatch via GroupManager.sendGroupMessage. The function
                // is fire-and-forget, so we treat any thrown exception as failure.
                try {
                    groupManager.sendGroupMessage(recipient, content)
                    manager.markAsSent(messageId)
                    Log.d(TAG, "Scheduled group message $messageId delivered")
                    Result.success()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to deliver scheduled group message $messageId: ${e.message}")
                    manager.markAsFailed(messageId)
                    if (runAttemptCount < 3) Result.retry() else Result.failure()
                }
            } else {
                val sendResult = repository.sendTextMessage(recipient, content)
                if (sendResult.isSuccess) {
                    manager.markAsSent(messageId)
                    Log.d(TAG, "Scheduled message $messageId delivered successfully")
                    Result.success()
                } else {
                    val err = sendResult.exceptionOrNull()?.message ?: "unknown error"
                    Log.e(TAG, "Failed to deliver scheduled message $messageId: $err")
                    manager.markAsFailed(messageId)
                    if (runAttemptCount < 3) Result.retry() else Result.failure()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception delivering scheduled message $messageId: ${e.message}", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "ScheduledMessageWorker"
    }
}

/**
 * Hilt entry point for WorkManager to access singletons without @HiltWorker.
 * Add this interface to a file visible to Hilt's component scan (it lives here
 * alongside its only consumer).
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface ScheduledMessageWorkerEntryPoint {
    fun scheduledMessageManager(): ScheduledMessageManager
    fun messageRepository(): com.oshi.messenger.data.repository.MessageRepository
    fun groupManager(): GroupManager
}
