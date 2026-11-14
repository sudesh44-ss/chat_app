package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.MessageReactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageReactionDao {

    @Query("SELECT * FROM message_reactions WHERE messageId = :messageId ORDER BY createdAt ASC")
    fun getReactionsForMessage(messageId: String): Flow<List<MessageReactionEntity>>

    @Query("SELECT * FROM message_reactions WHERE messageId = :messageId ORDER BY createdAt ASC")
    suspend fun getReactionsListForMessage(messageId: String): List<MessageReactionEntity>

    @Query("""
        SELECT r.* FROM message_reactions r 
        INNER JOIN messages m ON r.messageId = m.packetId 
        WHERE m.conversationId = :conversationId 
        ORDER BY r.createdAt ASC
    """)
    fun getReactionsForConversation(conversationId: String): Flow<List<MessageReactionEntity>>

    @Query("SELECT * FROM message_reactions WHERE messageId = :messageId AND userNxId = :userNxId LIMIT 1")
    suspend fun getUserReaction(messageId: String, userNxId: String): MessageReactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReaction(reaction: MessageReactionEntity): Long

    @Query("DELETE FROM message_reactions WHERE messageId = :messageId AND userNxId = :userNxId")
    suspend fun removeReaction(messageId: String, userNxId: String): Int

    @Query("DELETE FROM message_reactions WHERE messageId = :messageId")
    suspend fun deleteReactionsForMessage(messageId: String): Int
}
