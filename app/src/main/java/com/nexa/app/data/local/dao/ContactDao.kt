package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import kotlinx.coroutines.flow.Flow

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

    @Query("DELETE FROM contacts WHERE nxId = :nxId")
    suspend fun deleteContact(nxId: String): Int

    @Query("SELECT COUNT(*) FROM contacts WHERE status = 'REQUEST_RECEIVED'")
    fun getPendingReceivedCount(): Flow<Int>

    @Query("SELECT * FROM contacts ORDER BY createdAtTimestamp DESC")
    fun getAllContacts(): Flow<List<ContactEntity>>
}
