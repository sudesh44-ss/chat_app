package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.PeerSyncCursorEntity

@Dao
interface PeerSyncCursorDao {

    @Query("SELECT * FROM peer_sync_cursors WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getCursor(conversationId: String): PeerSyncCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(cursor: PeerSyncCursorEntity): Long

    @Query("UPDATE peer_sync_cursors SET lastSyncTimestamp = :timestamp, lastSyncedPacketId = :packetId, updatedAt = :now WHERE conversationId = :conversationId")
    suspend fun updateSyncProgress(conversationId: String, timestamp: Long, packetId: String?, now: Long = System.currentTimeMillis()): Int
}
