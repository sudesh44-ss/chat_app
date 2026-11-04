package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM chats ORDER BY lastMessageTimestamp DESC")
    fun getChatsFlow(): Flow<List<ChatEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChat(chat: ChatEntity): Long

    @Query("SELECT * FROM chats WHERE participantNxId = :nxId LIMIT 1")
    suspend fun getChatByParticipant(nxId: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getChatById(conversationId: String): ChatEntity?

    @Query("UPDATE chats SET unreadCount = 0 WHERE conversationId = :conversationId")
    suspend fun resetUnreadCount(conversationId: String): Int

    @Query("DELETE FROM chats WHERE conversationId = :conversationId")
    suspend fun deleteChat(conversationId: String): Int
}
