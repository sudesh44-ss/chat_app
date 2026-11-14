package com.nexa.app.data.manager

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.core.util.SafeMessageParser
import com.nexa.app.data.local.dao.ScheduledMessageDao
import com.nexa.app.data.local.entity.ScheduledMessageEntity
import com.nexa.app.data.local.entity.toDomain
import com.nexa.app.data.worker.ScheduledMessageWorker
import com.nexa.app.domain.manager.ScheduledMessageManager
import com.nexa.app.domain.model.ScheduledMessage
import com.nexa.app.domain.model.ScheduledMessageStatus
import com.nexa.app.domain.model.TransportType
import com.nexa.app.domain.repository.ChatRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

class ScheduledMessageManagerImpl(
    private val context: Context? = null,
    private val scheduledMessageDao: ScheduledMessageDao,
    private val chatRepository: ChatRepository,
    private val secureStorage: SecureStorage? = null,
    private val workManager: WorkManager? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ScheduledMessageManager {

    private fun getWorkManagerInstance(): WorkManager? {
        return workManager ?: context?.let {
            try {
                WorkManager.getInstance(it)
            } catch (e: Exception) {
                Log.w(TAG, "WorkManager instance could not be initialized: ${e.message}")
                null
            }
        }
    }

    override suspend fun scheduleMessage(
        chatId: String,
        recipientId: String,
        content: String,
        scheduledAtUtcMillis: Long,
        messageType: String,
        attachmentReference: String?,
        replyToMessageId: String?,
        replyToSenderNxId: String?,
        replyToSnippet: String?,
        transportType: TransportType,
        destinationPhone: String?
    ): Result<ScheduledMessage> = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        if (scheduledAtUtcMillis <= now) {
            return@withContext Result.failure(IllegalArgumentException("Scheduled time must be in the future"))
        }

        val sanitizedText = SafeMessageParser.sanitizeInputText(content.trim())
        if (sanitizedText.isBlank() && attachmentReference.isNullOrBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Cannot schedule an empty message"))
        }

        val myNxId = secureStorage?.getActiveNxId() ?: "NX-ME"
        val scheduleId = UUID.randomUUID().toString()
        val messageId = UUID.randomUUID().toString()

        val entity = ScheduledMessageEntity(
            id = scheduleId,
            messageId = messageId,
            chatId = chatId,
            senderId = myNxId,
            recipientId = recipientId,
            messageType = messageType,
            content = sanitizedText,
            attachmentReference = attachmentReference,
            scheduledAt = scheduledAtUtcMillis,
            createdAt = now,
            status = ScheduledMessageStatus.SCHEDULED.name,
            sentAt = null,
            cancelledAt = null,
            replyToMessageId = replyToMessageId,
            replyToSenderNxId = replyToSenderNxId,
            replyToSnippet = replyToSnippet,
            transportType = transportType.name,
            destinationPhone = destinationPhone,
            failureReason = null
        )

        try {
            scheduledMessageDao.insert(entity)
            enqueueWorker(entity)
            Log.i(TAG, "Successfully scheduled message $scheduleId for $recipientId at $scheduledAtUtcMillis")
            Result.success(entity.toDomain())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist scheduled message $scheduleId", e)
            Result.failure(e)
        }
    }

    override suspend fun cancelScheduledMessage(id: String): Boolean = withContext(ioDispatcher) {
        try {
            val updated = scheduledMessageDao.cancelScheduleById(id, System.currentTimeMillis(), "Cancelled by user")
            getWorkManagerInstance()?.cancelUniqueWork(ScheduledMessageWorker.getWorkName(id))
            Log.i(TAG, "Cancelled scheduled message $id (rows affected: $updated)")
            updated > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling scheduled message $id", e)
            false
        }
    }

    override suspend fun cancelPendingSchedulesForChat(chatId: String): Int = withContext(ioDispatcher) {
        try {
            val active = scheduledMessageDao.getActiveSchedulesForChat(chatId)
            if (active.isEmpty()) return@withContext 0

            val wm = getWorkManagerInstance()
            for (item in active) {
                wm?.cancelUniqueWork(ScheduledMessageWorker.getWorkName(item.id))
            }

            val count = scheduledMessageDao.cancelActiveSchedulesForChat(
                chatId = chatId,
                cancelledAt = System.currentTimeMillis(),
                reason = "Superseded by subsequent normal outgoing message"
            )
            Log.i(TAG, "Automatically cancelled $count scheduled message(s) in chat $chatId due to subsequent outgoing message")
            count
        } catch (e: Exception) {
            Log.e(TAG, "Error auto-cancelling schedules for chat $chatId", e)
            0
        }
    }

    override fun observeActiveSchedulesForChat(chatId: String): Flow<List<ScheduledMessage>> {
        return scheduledMessageDao.observeActiveSchedulesForChat(chatId)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(ioDispatcher)
    }

    override fun observeAllSchedulesForChat(chatId: String): Flow<List<ScheduledMessage>> {
        return scheduledMessageDao.observeAllSchedulesForChat(chatId)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(ioDispatcher)
    }

    override suspend fun reconcilePendingSchedules(): Unit = withContext(ioDispatcher) {
        try {
            val active = scheduledMessageDao.getAllActiveSchedules()
            Log.i(TAG, "Reconciling ${active.size} active scheduled message(s)")
            val now = System.currentTimeMillis()

            for (schedule in active) {
                // Rule 1: Validate whether a normal message invalidated this schedule
                val newerNormalCount = scheduledMessageDao.countNewerNormalOutgoingMessages(schedule.chatId, schedule.createdAt)
                if (newerNormalCount > 0) {
                    Log.i(TAG, "Reconciliation: Invalidating schedule ${schedule.id} because $newerNormalCount normal message(s) were sent")
                    scheduledMessageDao.cancelScheduleById(schedule.id, now, "Superseded by subsequent normal outgoing message")
                    getWorkManagerInstance()?.cancelUniqueWork(ScheduledMessageWorker.getWorkName(schedule.id))
                    continue
                }

                // Rule 2: Re-enqueue worker if not expired
                enqueueWorker(schedule)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reconciling pending schedules", e)
        }
    }

    override suspend fun executeScheduledMessage(id: String): Result<Boolean> = withContext(ioDispatcher) {
        val schedule = scheduledMessageDao.getById(id)
            ?: return@withContext Result.failure(IllegalStateException("Schedule record $id does not exist"))

        if (schedule.status == ScheduledMessageStatus.SENT.name) {
            Log.i(TAG, "Schedule $id was already SENT, skipping")
            return@withContext Result.success(true)
        }

        if (schedule.status == ScheduledMessageStatus.CANCELLED.name) {
            Log.i(TAG, "Schedule $id was CANCELLED, skipping")
            return@withContext Result.success(false)
        }

        val now = System.currentTimeMillis()
        if (schedule.scheduledAt > now) {
            Log.w(TAG, "Schedule $id instant ${schedule.scheduledAt} has not arrived yet (now: $now)")
            return@withContext Result.failure(IllegalStateException("Scheduled instant not yet reached"))
        }

        // Tier 1 Validation: Check whether a normal outgoing message superseded this schedule
        val newerNormalCount = scheduledMessageDao.countNewerNormalOutgoingMessages(schedule.chatId, schedule.createdAt)
        if (newerNormalCount > 0) {
            Log.i(TAG, "Critical Rule Triggered: Schedule $id aborted because $newerNormalCount normal message(s) were sent in ${schedule.chatId}")
            scheduledMessageDao.cancelScheduleById(id, now, "Superseded by subsequent normal outgoing message")
            getWorkManagerInstance()?.cancelUniqueWork(ScheduledMessageWorker.getWorkName(id))
            return@withContext Result.success(false)
        }

        // Tier 2 Atomic Claim: Transition status to SENDING in a single atomic SQL statement
        val claimed = scheduledMessageDao.claimForSending(id, now)
        if (claimed == 0) {
            Log.w(TAG, "Schedule $id could not be claimed for sending (already claimed or cancelled)")
            return@withContext Result.success(false)
        }

        // Dispatch via existing ChatRepository outbox & E2EE pipeline
        val transport = try {
            TransportType.valueOf(schedule.transportType)
        } catch (e: Exception) {
            TransportType.INTERNET
        }

        val sendResult = chatRepository.sendMessage(
            recipientNxId = schedule.recipientId,
            text = schedule.content,
            transport = transport,
            destinationPhone = schedule.destinationPhone,
            replyToMessageId = schedule.replyToMessageId,
            isScheduledDispatch = true
        )

        if (sendResult.isSuccess) {
            scheduledMessageDao.markSent(id, System.currentTimeMillis())
            Log.i(TAG, "Scheduled message $id successfully sent via $transport")
            Result.success(true)
        } else {
            val error = sendResult.exceptionOrNull()
            Log.e(TAG, "Scheduled message $id dispatch failed: ${error?.message}", error)
            scheduledMessageDao.markPending(id, error?.message)
            Result.failure(error ?: Exception("Scheduled message transmission failed"))
        }
    }

    override suspend fun markScheduleFailed(id: String, reason: String): Unit = withContext(ioDispatcher) {
        scheduledMessageDao.markFailed(id, reason)
        Unit
    }

    private fun enqueueWorker(entity: ScheduledMessageEntity) {
        val wm = getWorkManagerInstance() ?: return
        val now = System.currentTimeMillis()
        val delayMillis = (entity.scheduledAt - now).coerceAtLeast(0L)

        val constraintsBuilder = Constraints.Builder()
        if (entity.transportType == TransportType.INTERNET.name) {
            constraintsBuilder.setRequiredNetworkType(NetworkType.CONNECTED)
        }

        val workRequest = OneTimeWorkRequestBuilder<ScheduledMessageWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(constraintsBuilder.build())
            .setInputData(workDataOf(ScheduledMessageWorker.KEY_SCHEDULED_MESSAGE_ID to entity.id))
            .addTag(ScheduledMessageWorker.TAG_SCHEDULED_MESSAGE)
            .addTag("chat_${entity.chatId}")
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()

        wm.enqueueUniqueWork(
            ScheduledMessageWorker.getWorkName(entity.id),
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        Log.d(TAG, "Enqueued unique work ${ScheduledMessageWorker.getWorkName(entity.id)} with delay ${delayMillis}ms")
    }

    companion object {
        private const val TAG = "ScheduledMessageMgr"
    }
}
