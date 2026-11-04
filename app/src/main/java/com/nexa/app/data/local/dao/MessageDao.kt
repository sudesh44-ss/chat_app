package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesFlow(conversationId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

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
}
