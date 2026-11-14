package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.ChatMemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMemberDao {

    @Query("SELECT * FROM chat_members WHERE chatId = :chatId AND membershipState = 'ACTIVE'")
    fun getActiveMembersFlow(chatId: String): Flow<List<ChatMemberEntity>>

    @Query("SELECT * FROM chat_members WHERE chatId = :chatId AND membershipState = 'ACTIVE'")
    suspend fun getActiveMembers(chatId: String): List<ChatMemberEntity>

    @Query("SELECT * FROM chat_members WHERE chatId = :chatId AND userNxId = :userNxId LIMIT 1")
    suspend fun getMember(chatId: String, userNxId: String): ChatMemberEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: ChatMemberEntity): Long

    @Query("UPDATE chat_members SET membershipState = :state, leftAt = :leftAt WHERE chatId = :chatId AND userNxId = :userNxId")
    suspend fun updateMembershipState(chatId: String, userNxId: String, state: String, leftAt: Long?): Int

    @Query("UPDATE chat_members SET role = :role WHERE chatId = :chatId AND userNxId = :userNxId")
    suspend fun updateMemberRole(chatId: String, userNxId: String, role: String): Int

    @Query("DELETE FROM chat_members WHERE chatId = :chatId AND userNxId = :userNxId")
    suspend fun removeMember(chatId: String, userNxId: String): Int

    @Query("SELECT COUNT(*) FROM chat_members WHERE chatId = :chatId AND membershipState = 'ACTIVE'")
    suspend fun countActiveMembers(chatId: String): Int
}
