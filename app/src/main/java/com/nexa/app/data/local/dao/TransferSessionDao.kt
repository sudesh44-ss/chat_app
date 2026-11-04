package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.TransferSessionEntity
import com.nexa.app.data.local.entity.TransferSessionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: TransferSessionEntity): Long

    @Query("SELECT * FROM transfer_sessions WHERE transferId = :transferId LIMIT 1")
    suspend fun getSession(transferId: Long): TransferSessionEntity?

    @Query("SELECT * FROM transfer_sessions ORDER BY priority DESC, lastActiveTimestamp DESC")
    fun getAllSessions(): Flow<List<TransferSessionEntity>>

    @Query("SELECT * FROM transfer_sessions WHERE status = :status ORDER BY priority DESC, lastActiveTimestamp ASC")
    suspend fun getSessionsByStatus(status: TransferSessionStatus): List<TransferSessionEntity>

    @Query("UPDATE transfer_sessions SET chunksCompleted = :completed, bitfieldBytes = :bitfield, lastActiveTimestamp = :now WHERE transferId = :transferId")
    suspend fun updateProgress(transferId: Long, completed: Int, bitfield: ByteArray, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE transfer_sessions SET status = :status, lastActiveTimestamp = :now WHERE transferId = :transferId")
    suspend fun updateStatus(transferId: Long, status: TransferSessionStatus, now: Long = System.currentTimeMillis()): Int

    @Query("SELECT * FROM transfer_sessions WHERE status != 'COMPLETED' AND lastActiveTimestamp < :thresholdEpoch")
    suspend fun getIncompleteTransfersOlderThan(thresholdEpoch: Long): List<TransferSessionEntity>

    @Query("DELETE FROM transfer_sessions WHERE transferId = :transferId")
    suspend fun deleteTransfer(transferId: Long): Int
}
