package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.ChatDraftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDraftDao {

    @Query("SELECT * FROM chat_drafts WHERE conversationId = :conversationId")
    fun getDraftFlow(conversationId: String): Flow<ChatDraftEntity?>

    @Query("SELECT * FROM chat_drafts WHERE conversationId = :conversationId")
    suspend fun getDraft(conversationId: String): ChatDraftEntity?

    @Query("SELECT * FROM chat_drafts ORDER BY updatedAt DESC")
    fun getAllDrafts(): Flow<List<ChatDraftEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDraft(draft: ChatDraftEntity): Long

    @Query("DELETE FROM chat_drafts WHERE conversationId = :conversationId")
    suspend fun deleteDraft(conversationId: String): Int

    @Query("DELETE FROM chat_drafts")
    suspend fun clearAllDrafts(): Int
}
