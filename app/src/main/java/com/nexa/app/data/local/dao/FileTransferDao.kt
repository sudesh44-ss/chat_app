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

    @Query("UPDATE file_transfers SET localUri = :localUri, status = :status WHERE transferId = :transferId")
    suspend fun updateLocalUriAndStatus(transferId: Long, localUri: String, status: String): Int

    @Query("UPDATE file_transfers SET securityState = :securityState WHERE transferId = :transferId")
    suspend fun updateSecurityState(transferId: Long, securityState: String): Int

    @Query("UPDATE file_transfers SET status = 'VERIFYING', securityState = 'SCANNING' WHERE transferId = :transferId")
    suspend fun markScanning(transferId: Long): Int

    @Query("UPDATE file_transfers SET securityState = :securityState, scanResult = :scanResult, threatDetails = :threatDetails WHERE transferId = :transferId")
    suspend fun updateScanResult(transferId: Long, securityState: String, scanResult: String, threatDetails: String?): Int

    @Query("UPDATE file_transfers SET fileClassification = :classification WHERE transferId = :transferId")
    suspend fun updateFileClassification(transferId: Long, classification: String): Int

    @Query("UPDATE file_transfers SET isExplicitlyAccepted = :accepted, securityState = :securityState WHERE transferId = :transferId")
    suspend fun updateExplicitAcceptance(transferId: Long, accepted: Boolean, securityState: String): Int

    @Query("UPDATE file_transfers SET quarantinePath = :quarantinePath WHERE transferId = :transferId")
    suspend fun updateQuarantinePath(transferId: Long, quarantinePath: String?): Int

    @Query("""
        UPDATE file_transfers
        SET localUri = :localUri, status = 'COMPLETED', securityState = 'RELEASED',
            isExplicitlyAccepted = 1, quarantinePath = NULL
        WHERE transferId = :transferId AND isIncoming = 1
          AND ((status = 'SCAN_PASSED' AND scanResult = 'SAFE')
            OR (status = 'REJECTED_MALICIOUS' AND scanResult = 'MALICIOUS'))
    """)
    suspend fun markReleased(transferId: Long, localUri: String): Int

    @Query("SELECT * FROM file_transfers WHERE securityState = :securityState")
    suspend fun getTransfersBySecurityState(securityState: String): List<TransferEntity>

    @Query("SELECT * FROM file_transfers WHERE transferId = :transferId LIMIT 1")
    suspend fun getTransferById(transferId: Long): TransferEntity?

    @Query("SELECT * FROM file_transfers WHERE status IN ('ACTIVE', 'PENDING')")
    suspend fun getActiveTransfers(): List<TransferEntity>

    @Query("SELECT * FROM file_transfers WHERE transferId = :transferId LIMIT 1")
    fun observeTransfer(transferId: Long): Flow<TransferEntity?>

    @Query("SELECT * FROM file_transfers WHERE conversationId = :conversationId ORDER BY timestamp DESC")
    fun observeTransfersForConversation(conversationId: String): Flow<List<TransferEntity>>

    @Query("SELECT * FROM file_transfers WHERE conversationId = :conversationId ORDER BY timestamp DESC")
    suspend fun getTransfersForConversation(conversationId: String): List<TransferEntity>

    @Query("DELETE FROM file_transfers WHERE transferId = :transferId")
    suspend fun deleteTransfer(transferId: Long): Int
}
