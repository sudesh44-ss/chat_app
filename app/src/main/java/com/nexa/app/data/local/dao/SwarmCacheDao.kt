package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.FileMetadataEntity
import com.nexa.app.data.local.entity.FileStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SwarmCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(entity: FileMetadataEntity): Long

    @Query("SELECT * FROM file_envelopes WHERE fileHash = :hash LIMIT 1")
    suspend fun getByHash(hash: String): FileMetadataEntity?

    @Query("SELECT * FROM file_envelopes WHERE fileHash = :hash LIMIT 1")
    fun observeByHash(hash: String): Flow<FileMetadataEntity?>

    @Query("SELECT * FROM file_envelopes WHERE conversationId = :conversationId ORDER BY lastAccessedTimestamp DESC")
    fun observeForConversation(conversationId: String): Flow<List<FileMetadataEntity>>

    @Query("SELECT * FROM file_envelopes WHERE status = :status ORDER BY lastAccessedTimestamp ASC")
    suspend fun getSeedingFilesOldestFirst(status: FileStatus = FileStatus.VERIFIED_SAFE): List<FileMetadataEntity>

    @Query("UPDATE file_envelopes SET status = :newStatus, localFilePath = :path, lastAccessedTimestamp = :timestamp WHERE fileHash = :hash")
    suspend fun updateStatus(
        hash: String,
        newStatus: FileStatus,
        path: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ): Int

    @Query("DELETE FROM file_envelopes WHERE fileHash = :hash")
    suspend fun deleteRecord(hash: String): Int
}
