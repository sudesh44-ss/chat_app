package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.StatusViewEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StatusViewDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordView(view: StatusViewEntity): Long

    @Query("SELECT * FROM status_views WHERE statusId = :statusId ORDER BY viewedAt DESC")
    suspend fun getViewersForStatus(statusId: String): List<StatusViewEntity>

    @Query("SELECT * FROM status_views WHERE statusId = :statusId ORDER BY viewedAt DESC")
    fun getViewersForStatusFlow(statusId: String): Flow<List<StatusViewEntity>>

    @Query("SELECT COUNT(*) FROM status_views WHERE statusId = :statusId")
    suspend fun getViewCountForStatus(statusId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM status_views WHERE statusId = :statusId AND viewerNxId = :viewerNxId)")
    suspend fun hasUserViewed(statusId: String, viewerNxId: String): Boolean

    @Query("DELETE FROM status_views WHERE statusId = :statusId")
    suspend fun deleteViewsForStatus(statusId: String): Int
}
