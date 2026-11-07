package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC, rowid ASC")
    fun getMessagesFlow(conversationId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIfNotExists(message: MessageEntity): Long

    @Query("UPDATE messages SET status = :status WHERE packetId = :packetId")
    suspend fun updateMessageStatus(packetId: String, status: String): Int

    @Query("SELECT * FROM messages WHERE status = 'PENDING' AND isOutgoing = 1 ORDER BY timestamp ASC")
    suspend fun getPendingOutgoingMessages(): List<MessageEntity>

    @Query("UPDATE messages SET status = 'READ' WHERE conversationId = :conversationId AND isOutgoing = 0 AND status != 'READ'")
    suspend fun markMessagesAsRead(conversationId: String): Int

    @Query("SELECT * FROM messages WHERE packetId = :packetId LIMIT 1")
    suspend fun getMessageByPacketId(packetId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestMessage(conversationId: String): MessageEntity?

    @Query("""
        SELECT * FROM messages 
        WHERE conversationId = :conversationId 
          AND isPinned = 1 
          AND (pinnedExpiresAt IS NULL OR pinnedExpiresAt > :currentTimeMillis)
        ORDER BY pinnedAt DESC, timestamp DESC 
        LIMIT 4
    """)
    fun getPinnedMessagesFlow(conversationId: String, currentTimeMillis: Long): Flow<List<MessageEntity>>

    @Query("""
        SELECT * FROM messages 
        WHERE conversationId = :conversationId 
          AND isPinned = 1 
          AND (pinnedExpiresAt IS NULL OR pinnedExpiresAt > :currentTimeMillis)
        ORDER BY pinnedAt DESC, timestamp DESC 
        LIMIT 4
    """)
    suspend fun getPinnedMessages(conversationId: String, currentTimeMillis: Long): List<MessageEntity>

    @Query("""
        SELECT COUNT(*) FROM messages 
        WHERE conversationId = :conversationId 
          AND isPinned = 1 
          AND (pinnedExpiresAt IS NULL OR pinnedExpiresAt > :currentTimeMillis)
    """)
    suspend fun getPinnedMessagesCount(conversationId: String, currentTimeMillis: Long): Int

    @Query("UPDATE messages SET isPinned = :isPinned, pinnedExpiresAt = :pinnedExpiresAt, pinnedAt = :pinnedAt WHERE packetId = :packetId")
    suspend fun updatePinStatus(packetId: String, isPinned: Boolean, pinnedExpiresAt: Long?, pinnedAt: Long?): Int

    @Query("UPDATE messages SET isPinned = 0, pinnedExpiresAt = NULL, pinnedAt = NULL WHERE packetId = :packetId")
    suspend fun unpinMessage(packetId: String): Int

    @Query("""
        UPDATE messages 
        SET isPinned = 0, pinnedExpiresAt = NULL, pinnedAt = NULL 
        WHERE conversationId = :conversationId 
        AND isPinned = 1 
        AND pinnedExpiresAt IS NOT NULL 
        AND pinnedExpiresAt <= :currentTimeMillis
    """)
    suspend fun clearExpiredPins(conversationId: String, currentTimeMillis: Long): Int

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String): Int
}
