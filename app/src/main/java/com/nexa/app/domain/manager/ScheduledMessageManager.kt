package com.nexa.app.domain.manager

import com.nexa.app.domain.model.ScheduledMessage
import com.nexa.app.domain.model.TransportType
import kotlinx.coroutines.flow.Flow

interface ScheduledMessageManager {

    /**
     * Schedules a message for future delivery.
     * Validates that scheduledAtUtcMillis is in the future.
     * Persists record to SQLCipher Room database.
     * Enqueues persistent OneTimeWorkRequest with WorkManager.
     */
    suspend fun scheduleMessage(
        chatId: String,
        recipientId: String,
        content: String,
        scheduledAtUtcMillis: Long,
        messageType: String = "TEXT",
        attachmentReference: String? = null,
        replyToMessageId: String? = null,
        replyToSenderNxId: String? = null,
        replyToSnippet: String? = null,
        transportType: TransportType = TransportType.INTERNET,
        destinationPhone: String? = null
    ): Result<ScheduledMessage>

    /**
     * Cancels an individual scheduled message by ID.
     * Cancels associated WorkManager task and updates database status to CANCELLED.
     */
    suspend fun cancelScheduledMessage(id: String): Boolean

    /**
     * Critical Rule: Automatically cancels all active scheduled messages in a given chat
     * when a subsequent normal outgoing message is committed to local storage.
     */
    suspend fun cancelPendingSchedulesForChat(chatId: String): Int

    /**
     * Observes active (SCHEDULED or PENDING) messages for a conversation.
     */
    fun observeActiveSchedulesForChat(chatId: String): Flow<List<ScheduledMessage>>

    /**
     * Observes all scheduled message records for a conversation.
     */
    fun observeAllSchedulesForChat(chatId: String): Flow<List<ScheduledMessage>>

    /**
     * Reconciles active schedules on application startup, reboot, or network transitions.
     */
    suspend fun reconcilePendingSchedules()

    /**
     * Executes the scheduled message transmission.
     * Invoked by ScheduledMessageWorker or test harnesses.
     */
    suspend fun executeScheduledMessage(id: String): Result<Boolean>

    /**
     * Marks a schedule as failed after terminal retry attempts.
     */
    suspend fun markScheduleFailed(id: String, reason: String)
}
