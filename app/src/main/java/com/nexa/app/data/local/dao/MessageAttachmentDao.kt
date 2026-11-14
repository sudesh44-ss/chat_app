package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nexa.app.data.local.entity.MessageAttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageAttachmentDao {

    @Query("SELECT * FROM message_attachments WHERE messageId = :messageId ORDER BY createdAt ASC")
    fun getAttachmentsForMessage(messageId: String): Flow<List<MessageAttachmentEntity>>

    @Query("SELECT * FROM message_attachments WHERE messageId = :messageId ORDER BY createdAt ASC")
    suspend fun getAttachmentsList(messageId: String): List<MessageAttachmentEntity>

    @Query("SELECT * FROM message_attachments WHERE attachmentId = :attachmentId")
    suspend fun getAttachmentById(attachmentId: String): MessageAttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(attachment: MessageAttachmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<MessageAttachmentEntity>): List<Long>

    @Update
    suspend fun updateAttachment(attachment: MessageAttachmentEntity): Int

    @Query("UPDATE message_attachments SET transferState = :transferState, localPathReference = COALESCE(:localPath, localPathReference) WHERE attachmentId = :attachmentId")
    suspend fun updateTransferState(attachmentId: String, transferState: String, localPath: String?): Int

    @Query("UPDATE message_attachments SET securityScanState = :securityScanState WHERE attachmentId = :attachmentId")
    suspend fun updateSecurityScanState(attachmentId: String, securityScanState: String): Int

    @Query("DELETE FROM message_attachments WHERE attachmentId = :attachmentId")
    suspend fun deleteAttachment(attachmentId: String): Int

    @Query("DELETE FROM message_attachments WHERE messageId = :messageId")
    suspend fun deleteAttachmentsForMessage(messageId: String): Int
}
