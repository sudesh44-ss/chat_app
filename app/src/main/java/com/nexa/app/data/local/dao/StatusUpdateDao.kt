package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.StatusUpdateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StatusUpdateDao {

    @Query("SELECT * FROM status_updates WHERE expiresAt > :currentTime ORDER BY createdAt DESC")
    fun getActiveStatusesFlow(currentTime: Long): Flow<List<StatusUpdateEntity>>

    @Query("SELECT * FROM status_updates ORDER BY createdAt DESC")
    fun getAllStatusesFlow(): Flow<List<StatusUpdateEntity>>

    @Query("SELECT * FROM status_updates ORDER BY createdAt DESC")
    suspend fun getAllStatuses(): List<StatusUpdateEntity>

    @Query("SELECT * FROM status_updates WHERE expiresAt > :currentTime ORDER BY createdAt DESC")
    suspend fun getActiveStatuses(currentTime: Long): List<StatusUpdateEntity>

    @Query("SELECT * FROM status_updates WHERE userNxId = :userNxId AND expiresAt > :currentTime ORDER BY createdAt DESC")
    suspend fun getUserActiveStatuses(userNxId: String, currentTime: Long): List<StatusUpdateEntity>

    @Query("SELECT * FROM status_updates WHERE userNxId = :userNxId AND expiresAt > :currentTime ORDER BY createdAt ASC")
    fun getUserActiveStatusesFlow(userNxId: String, currentTime: Long): Flow<List<StatusUpdateEntity>>

    @Query("SELECT * FROM status_updates WHERE statusId = :statusId LIMIT 1")
    suspend fun getStatus(statusId: String): StatusUpdateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStatus(status: StatusUpdateEntity): Long

    @Query("UPDATE status_updates SET isViewed = 1, viewedAt = :viewedAt WHERE statusId = :statusId")
    suspend fun markStatusViewed(statusId: String, viewedAt: Long): Int

    @Query("UPDATE status_updates SET viewCount = viewCount + 1 WHERE statusId = :statusId")
    suspend fun incrementViewCount(statusId: String): Int

    @Query("DELETE FROM status_updates WHERE expiresAt <= :currentTime")
    suspend fun deleteExpiredStatuses(currentTime: Long): Int

    @Query("DELETE FROM status_updates WHERE statusId = :statusId")
    suspend fun deleteStatus(statusId: String): Int

    @Query("DELETE FROM status_updates WHERE userNxId = :userNxId")
    suspend fun deleteUserStatuses(userNxId: String): Int
}
