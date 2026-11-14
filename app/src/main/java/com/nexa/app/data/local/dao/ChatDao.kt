package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    /**
     * Deterministic chat list ordering:
     * 1. Unarchived only
     * 2. Pinned chats on top, ordered by pinPosition ASC
     * 3. Most recently active chats (lastMessageTimestamp DESC)
     * 4. Tie-breaker by conversationId ASC
     */
    @Query("""
        SELECT * FROM chats 
        WHERE isArchived = 0 
        ORDER BY isPinned DESC, pinPosition ASC, lastMessageTimestamp DESC, conversationId ASC
    """)
    fun getChatsFlow(): Flow<List<ChatEntity>>

    @Query("""
        SELECT * FROM chats 
        WHERE isArchived = 1 
        ORDER BY lastMessageTimestamp DESC, conversationId ASC
    """)
    fun getArchivedChatsFlow(): Flow<List<ChatEntity>>

    @Query("""
        SELECT * FROM chats 
        WHERE isArchived = 0 AND chatType = :chatType 
        ORDER BY isPinned DESC, pinPosition ASC, lastMessageTimestamp DESC, conversationId ASC
    """)
    fun getChatsByTypeFlow(chatType: String): Flow<List<ChatEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChat(chat: ChatEntity): Long

    @Query("SELECT * FROM chats WHERE participantNxId = :nxId LIMIT 1")
    suspend fun getChatByParticipant(nxId: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getChatById(conversationId: String): ChatEntity?

    @Query("UPDATE chats SET unreadCount = 0, hasManualUnread = 0 WHERE conversationId = :conversationId")
    suspend fun resetUnreadCount(conversationId: String): Int

    @Query("UPDATE chats SET hasManualUnread = 1, updatedAt = :updatedAt WHERE conversationId = :conversationId")
    suspend fun markConversationUnread(conversationId: String, updatedAt: Long): Int

    @Query("DELETE FROM chats WHERE conversationId = :conversationId")
    suspend fun deleteChat(conversationId: String): Int

    @Query("UPDATE chats SET participantName = :newName WHERE participantNxId = :nxId")
    suspend fun updateParticipantName(nxId: String, newName: String): Int

    @Query("UPDATE chats SET lastMessageText = '', unreadCount = 0, hasManualUnread = 0 WHERE conversationId = :conversationId")
    suspend fun clearLastMessageText(conversationId: String): Int

    @Query("""
        UPDATE chats 
        SET isPinned = :isPinned, pinPosition = :pinPosition, pinnedAt = :pinnedAt, updatedAt = :updatedAt 
        WHERE conversationId = :conversationId
    """)
    suspend fun updatePinStatus(
        conversationId: String,
        isPinned: Boolean,
        pinPosition: Int,
        pinnedAt: Long?,
        updatedAt: Long
    ): Int

    @Query("""
        UPDATE chats 
        SET isArchived = :isArchived, archivedAt = :archivedAt, updatedAt = :updatedAt 
        WHERE conversationId = :conversationId
    """)
    suspend fun updateArchiveStatus(
        conversationId: String,
        isArchived: Boolean,
        archivedAt: Long?,
        updatedAt: Long
    ): Int

    @Query("""
        UPDATE chats 
        SET isMuted = :isMuted, mutedUntil = :mutedUntil, updatedAt = :updatedAt 
        WHERE conversationId = :conversationId
    """)
    suspend fun updateMuteStatus(
        conversationId: String,
        isMuted: Boolean,
        mutedUntil: Long,
        updatedAt: Long
    ): Int

    @Query("""
        UPDATE chats 
        SET lastMessageId = :lastMessageId,
            lastMessageText = :text,
            lastMessageSenderNxId = :lastSenderNxId,
            lastMessageTimestamp = :timestamp,
            updatedAt = :timestamp
        WHERE conversationId = :conversationId
    """)
    suspend fun updateLastMessage(
        conversationId: String,
        text: String,
        timestamp: Long,
        lastMessageId: String?,
        lastSenderNxId: String?
    ): Int

    @Query("UPDATE chats SET unreadCount = unreadCount + 1 WHERE conversationId = :conversationId")
    suspend fun incrementUnread(conversationId: String): Int

    @Query("SELECT COUNT(*) FROM chats WHERE isArchived = 0")
    suspend fun countActiveChats(): Int
}
