package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.MessageDedupEntity

@Dao
interface MessageDedupDao {

    @Query("SELECT * FROM message_dedup_index WHERE packetId = :packetId LIMIT 1")
    suspend fun getDedupEntry(packetId: String): MessageDedupEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDedupEntry(entry: MessageDedupEntity): Long

    @Query("UPDATE message_dedup_index SET status = :status WHERE packetId = :packetId")
    suspend fun updateStatus(packetId: String, status: String): Int

    @Query("SELECT packetId FROM message_dedup_index WHERE conversationId = :conversationId ORDER BY firstObservedAt ASC")
    suspend fun getPacketIdsForConversation(conversationId: String): List<String>

    @Query("""
        SELECT packetId FROM message_dedup_index 
        WHERE conversationId = :conversationId AND firstObservedAt >= :sinceTimestamp 
        ORDER BY firstObservedAt ASC 
        LIMIT :limit
    """)
    suspend fun getRecentPacketIds(conversationId: String, sinceTimestamp: Long, limit: Int = 100): List<String>

    @Query("SELECT COUNT(*) FROM message_dedup_index WHERE packetId = :packetId")
    suspend fun hasPacket(packetId: String): Int
}
