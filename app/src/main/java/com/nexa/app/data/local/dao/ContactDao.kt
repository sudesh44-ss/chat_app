package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull

@Dao
interface ContactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContact(contact: ContactEntity): Long

    @Query("SELECT * FROM contacts WHERE status = :status ORDER BY createdAtTimestamp DESC")
    fun getContactsByStatus(status: ContactStatus): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE nxId = :nxId LIMIT 1")
    suspend fun getContact(nxId: String): ContactEntity?

    @Query("UPDATE contacts SET status = :newStatus WHERE nxId = :nxId")
    suspend fun updateStatus(nxId: String, newStatus: ContactStatus): Int

    @Query("UPDATE contacts SET isStatusMuted = :isMuted WHERE nxId = :nxId")
    suspend fun updateStatusMuted(nxId: String, isMuted: Boolean): Int

    @Query("DELETE FROM contacts WHERE nxId = :nxId")
    suspend fun deleteContact(nxId: String): Int

    @Query("SELECT COUNT(*) FROM contacts WHERE status = 'REQUEST_RECEIVED'")
    fun getPendingReceivedCount(): Flow<Int>

    @Query("SELECT * FROM contacts ORDER BY createdAtTimestamp DESC")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Query("UPDATE contacts SET customNickname = :nickname WHERE nxId = :nxId")
    suspend fun updateNickname(nxId: String, nickname: String?): Int

    @Query("UPDATE contacts SET deviceContactName = :deviceContactName WHERE nxId = :nxId")
    suspend fun updateDeviceContactName(nxId: String, deviceContactName: String?): Int

    @Query("""
        SELECT * FROM contacts 
        WHERE displayName LIKE '%' || :query || '%' 
           OR (customNickname IS NOT NULL AND customNickname LIKE '%' || :query || '%') 
           OR (deviceContactName IS NOT NULL AND deviceContactName LIKE '%' || :query || '%') 
           OR nxId LIKE '%' || :query || '%' 
        ORDER BY createdAtTimestamp DESC
    """)
    fun searchContacts(query: String): Flow<List<ContactEntity>>

    @Query("UPDATE contacts SET isPhoneSharedByPeer = :isShared, phoneNumber = :phone WHERE nxId = :nxId")
    suspend fun updatePeerPhoneSharing(nxId: String, isShared: Boolean, phone: String?): Int

    @Query("UPDATE contacts SET nexaAvatarUrl = :avatarUrl WHERE nxId = :nxId")
    suspend fun updateAvatarUrl(nxId: String, avatarUrl: String?): Int

    @Query("UPDATE contacts SET deviceContactPhotoUri = :photoUri WHERE nxId = :nxId")
    suspend fun updateDeviceContactPhotoUri(nxId: String, photoUri: String?): Int
}

suspend fun ContactDao.getAllContactsList(): List<ContactEntity> {
    return this.getAllContacts().firstOrNull() ?: emptyList()
}
