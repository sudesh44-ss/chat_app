package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nexa.app.data.local.entity.ScheduledMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ScheduledMessageEntity): Long

    @Update
    suspend fun update(entity: ScheduledMessageEntity): Int

    @Query("SELECT * FROM scheduled_messages WHERE id = :id")
    suspend fun getById(id: String): ScheduledMessageEntity?

    @Query("SELECT * FROM scheduled_messages WHERE messageId = :messageId")
    suspend fun getByMessageId(messageId: String): ScheduledMessageEntity?

    @Query("SELECT * FROM scheduled_messages WHERE chatId = :chatId AND status IN ('SCHEDULED', 'PENDING') ORDER BY scheduledAt ASC")
    fun observeActiveSchedulesForChat(chatId: String): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages WHERE chatId = :chatId ORDER BY scheduledAt DESC")
    fun observeAllSchedulesForChat(chatId: String): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages WHERE chatId = :chatId AND status IN ('SCHEDULED', 'PENDING') ORDER BY scheduledAt ASC")
    suspend fun getActiveSchedulesForChat(chatId: String): List<ScheduledMessageEntity>

    @Query("SELECT * FROM scheduled_messages WHERE status IN ('SCHEDULED', 'PENDING') ORDER BY scheduledAt ASC")
    suspend fun getAllActiveSchedules(): List<ScheduledMessageEntity>

    @Query("UPDATE scheduled_messages SET status = 'CANCELLED', cancelledAt = :cancelledAt, failureReason = :reason WHERE chatId = :chatId AND status IN ('SCHEDULED', 'PENDING')")
    suspend fun cancelActiveSchedulesForChat(chatId: String, cancelledAt: Long, reason: String): Int

    @Query("UPDATE scheduled_messages SET status = 'CANCELLED', cancelledAt = :cancelledAt, failureReason = :reason WHERE id = :id AND status IN ('SCHEDULED', 'PENDING')")
    suspend fun cancelScheduleById(id: String, cancelledAt: Long, reason: String): Int

    /**
     * Atomically transitions status to SENDING if the scheduled time has arrived and the schedule is still active.
     * Returns 1 if claimed, 0 if already claimed or cancelled.
     */
    @Query("UPDATE scheduled_messages SET status = 'SENDING' WHERE id = :id AND status IN ('SCHEDULED', 'PENDING') AND scheduledAt <= :now")
    suspend fun claimForSending(id: String, now: Long): Int

    @Query("UPDATE scheduled_messages SET status = 'SENT', sentAt = :sentAt WHERE id = :id")
    suspend fun markSent(id: String, sentAt: Long): Int

    @Query("UPDATE scheduled_messages SET status = 'PENDING', failureReason = :reason WHERE id = :id")
    suspend fun markPending(id: String, reason: String?): Int

    @Query("UPDATE scheduled_messages SET status = 'FAILED', failureReason = :reason WHERE id = :id")
    suspend fun markFailed(id: String, reason: String?): Int

    /**
     * Checks whether any normal outgoing message was committed in this chat after this schedule was created.
     * Excludes scheduled message IDs so scheduled dispatches do not cancel future scheduled messages.
     */
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :chatId AND isOutgoing = 1 AND timestamp >= :createdAt AND packetId NOT IN (SELECT messageId FROM scheduled_messages WHERE chatId = :chatId)")
    suspend fun countNewerNormalOutgoingMessages(chatId: String, createdAt: Long): Int

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
