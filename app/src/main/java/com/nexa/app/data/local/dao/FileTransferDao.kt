package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.TransferEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileTransferDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransfer(transfer: TransferEntity): Long

    @Query("UPDATE file_transfers SET chunksCompleted = :chunksCompleted, status = :status WHERE transferId = :transferId")
    suspend fun updateTransferProgress(transferId: Long, chunksCompleted: Int, status: String): Int

    @Query("UPDATE file_transfers SET status = :status WHERE transferId = :transferId")
    suspend fun updateStatus(transferId: Long, status: String): Int

    @Query("SELECT * FROM file_transfers WHERE transferId = :transferId LIMIT 1")
    suspend fun getTransferById(transferId: Long): TransferEntity?

    @Query("SELECT * FROM file_transfers WHERE status IN ('ACTIVE', 'PENDING')")
    suspend fun getActiveTransfers(): List<TransferEntity>

    @Query("SELECT * FROM file_transfers WHERE transferId = :transferId LIMIT 1")
    fun observeTransfer(transferId: Long): Flow<TransferEntity?>

    @Query("SELECT * FROM file_transfers WHERE conversationId = :conversationId ORDER BY timestamp DESC")
    fun observeTransfersForConversation(conversationId: String): Flow<List<TransferEntity>>

    @Query("DELETE FROM file_transfers WHERE transferId = :transferId")
    suspend fun deleteTransfer(transferId: Long): Int
}
