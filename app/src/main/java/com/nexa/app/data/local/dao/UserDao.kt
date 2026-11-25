package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveUserProfile(user: UserEntity): Long

    @Query("SELECT * FROM user_profile WHERE nxId = :nxId LIMIT 1")
    fun getProfileByNxIdFlow(nxId: String): Flow<UserEntity?>

    @Query("SELECT * FROM user_profile WHERE nxId = :nxId LIMIT 1")
    suspend fun getProfileByNxId(nxId: String): UserEntity?

    @Query("SELECT * FROM user_profile ORDER BY CASE WHEN nxId = 'NX-DEV-USER' THEN 1 ELSE 0 END, createdAt DESC LIMIT 1")
    suspend fun getLocalProfile(): UserEntity?

    @Query("SELECT * FROM user_profile ORDER BY CASE WHEN nxId = 'NX-DEV-USER' THEN 1 ELSE 0 END, createdAt DESC LIMIT 1")
    fun getActiveUserFlow(): Flow<UserEntity?>

    @Query("SELECT nxId FROM user_profile ORDER BY CASE WHEN nxId = 'NX-DEV-USER' THEN 1 ELSE 0 END, createdAt DESC LIMIT 1")
    suspend fun getActiveNxId(): String?

    @Query("SELECT COUNT(*) FROM user_profile WHERE isVerified = 1 AND nxId != 'NX-DEV-USER'")
    suspend fun countVerifiedUsers(): Int

    suspend fun hasActiveSession(): Boolean = countVerifiedUsers() > 0

    @Query("UPDATE user_profile SET fullName = :fullName WHERE nxId = :nxId")
    suspend fun updateFullName(nxId: String, fullName: String): Int

    @Query("UPDATE user_profile SET phoneNumber = :phoneNumber WHERE nxId = :nxId")
    suspend fun updatePhoneNumber(nxId: String, phoneNumber: String): Int

    @Query("DELETE FROM user_profile WHERE nxId = 'NX-DEV-USER'")
    suspend fun deleteDemoProfile(): Int

    @Query("DELETE FROM user_profile WHERE nxId = :nxId")
    suspend fun deleteProfileByNxId(nxId: String): Int

    @Query("DELETE FROM user_profile")
    suspend fun clearProfile(): Int
}
