package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.QuarantineEntity
import com.nexa.app.data.local.entity.QuarantineStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface QuarantineDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: QuarantineEntity): Long

    @Query("SELECT * FROM quarantine_logs ORDER BY detectedTimestamp DESC")
    fun getAllLogs(): Flow<List<QuarantineEntity>>

    @Query("SELECT * FROM quarantine_logs WHERE fileHash = :hash LIMIT 1")
    suspend fun getLog(hash: String): QuarantineEntity?

    @Query("UPDATE quarantine_logs SET status = :status WHERE fileHash = :hash")
    suspend fun updateStatus(hash: String, status: QuarantineStatus): Int

    @Query("DELETE FROM quarantine_logs WHERE fileHash = :hash")
    suspend fun deleteLog(hash: String): Int

    @Query("SELECT COUNT(*) FROM quarantine_logs WHERE status = 'ISOLATED'")
    fun getIsolatedThreatCount(): Flow<Int>
}
