package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("""
        SELECT * FROM messages 
        WHERE conversationId = :conversationId AND isDeleted = 0 
        ORDER BY timestamp ASC, localSequence ASC, rowid ASC
    """)
    fun getMessagesFlow(conversationId: String): Flow<List<MessageEntity>>

    @Query("""
        SELECT * FROM messages 
        WHERE conversationId = :conversationId AND isDeleted = 0 
        ORDER BY timestamp DESC, localSequence DESC, rowid DESC 
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getMessagesPaged(conversationId: String, limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(localSequence), 0) + 1 FROM messages WHERE conversationId = :conversationId")
    suspend fun getNextLocalSequence(conversationId: String): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIfNotExists(message: MessageEntity): Long

    @Query("UPDATE messages SET status = :status WHERE packetId = :packetId")
    suspend fun updateMessageStatus(packetId: String, status: String): Int

    @Query("UPDATE messages SET isDeleted = 1 WHERE packetId = :packetId")
    suspend fun softDeleteMessage(packetId: String): Int

    @Query("UPDATE messages SET isStarred = :isStarred WHERE packetId = :packetId")
    suspend fun updateStarredStatus(packetId: String, isStarred: Boolean): Int

    @Query("SELECT * FROM messages WHERE status = 'PENDING' AND isOutgoing = 1 ORDER BY timestamp ASC")
    suspend fun getPendingOutgoingMessages(): List<MessageEntity>

    @Query("UPDATE messages SET status = 'READ' WHERE conversationId = :conversationId AND isOutgoing = 0 AND status != 'READ'")
    suspend fun markMessagesAsRead(conversationId: String): Int

    @Query("SELECT * FROM messages WHERE packetId = :packetId LIMIT 1")
    suspend fun getMessageByPacketId(packetId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND isDeleted = 0 ORDER BY timestamp DESC, localSequence DESC LIMIT 1")
    suspend fun getLatestMessage(conversationId: String): MessageEntity?

    @Query("""
        SELECT * FROM messages 
        WHERE conversationId = :conversationId 
          AND isPinned = 1 
          AND isDeleted = 0
          AND (pinnedExpiresAt IS NULL OR pinnedExpiresAt > :currentTimeMillis)
        ORDER BY pinnedAt DESC, timestamp DESC 
        LIMIT 4
    """)
    fun getPinnedMessagesFlow(conversationId: String, currentTimeMillis: Long): Flow<List<MessageEntity>>

    @Query("""
        SELECT * FROM messages 
        WHERE conversationId = :conversationId 
          AND isPinned = 1 
          AND isDeleted = 0
          AND (pinnedExpiresAt IS NULL OR pinnedExpiresAt > :currentTimeMillis)
        ORDER BY pinnedAt DESC, timestamp DESC 
        LIMIT 4
    """)
    suspend fun getPinnedMessages(conversationId: String, currentTimeMillis: Long): List<MessageEntity>

    @Query("""
        SELECT COUNT(*) FROM messages 
        WHERE conversationId = :conversationId 
          AND isPinned = 1 
          AND isDeleted = 0
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

    @Query("SELECT packetId FROM messages WHERE conversationId = :conversationId AND timestamp > :afterTimestamp AND isDeleted = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getMessageIdsAfterTimestamp(conversationId: String, afterTimestamp: Long, limit: Int): List<String>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND timestamp > :afterTimestamp AND isDeleted = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getMessagesAfterTimestamp(conversationId: String, afterTimestamp: Long, limit: Int): List<MessageEntity>

    @Query("""
        SELECT * FROM messages 
        WHERE conversationId = :conversationId 
          AND isDeleted = 0
          AND plainTextContent LIKE '%' || :query || '%' 
        ORDER BY timestamp DESC, localSequence DESC
    """)
    suspend fun searchMessagesInConversation(conversationId: String, query: String): List<MessageEntity>

    @Query("""
        SELECT * FROM messages 
        WHERE conversationId = :conversationId 
          AND isDeleted = 0
          AND timestamp >= :startOfDay 
          AND timestamp < :endOfDay 
        ORDER BY timestamp ASC, localSequence ASC 
        LIMIT 1
    """)
    suspend fun getFirstMessageOnDate(conversationId: String, startOfDay: Long, endOfDay: Long): MessageEntity?

    @Query("""
        SELECT * FROM messages 
        WHERE conversationId = :conversationId 
          AND isDeleted = 0
          AND timestamp >= :startOfDay 
          AND timestamp < :endOfDay 
        ORDER BY timestamp ASC, localSequence ASC
    """)
    suspend fun getMessagesOnDate(conversationId: String, startOfDay: Long, endOfDay: Long): List<MessageEntity>

    @Query("""
        SELECT COUNT(*) FROM messages 
        WHERE conversationId = :conversationId 
          AND isDeleted = 0
          AND timestamp >= :startOfDay 
          AND timestamp < :endOfDay
    """)
    suspend fun countMessagesOnDate(conversationId: String, startOfDay: Long, endOfDay: Long): Int

    @Query("""
        SELECT COUNT(*) FROM messages 
        WHERE conversationId = :conversationId 
          AND isDeleted = 0
          AND timestamp < :timestamp
    """)
    suspend fun countMessagesBeforeTimestamp(conversationId: String, timestamp: Long): Int
}
