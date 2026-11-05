package com.nexa.app.domain.repository

import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import kotlinx.coroutines.flow.Flow

/**
 * Domain repository contract managing Contact Discovery, X3DH Handshakes, and Relationship States.
 */
interface ContactRepository {

    /**
     * Solves client-side Micro-PoW, derives initial X3DH keys, persists REQUEST_SENT locally,
     * and dispatches the sealed envelope to the blind server.
     */
    suspend fun sendFriendRequest(targetNxId: String, introMessage: String?): Result<Unit>

    /**
     * Unlocks Double Ratchet session, updates status to MUTUAL_FRIEND, and dispatches
     * an encrypted cryptographic ACCEPT packet to the initiator.
     */
    suspend fun acceptFriendRequest(contact: ContactEntity): Result<Unit>

    /**
     * Rejects or permanently blocks a contact.
     * If block is true: updates local DB to BLOCKED and silently drops all future packets.
     * If block is false: deletes local row.
     * Dispatches ZERO response to network (initiator remains permanently unaware).
     */
    suspend fun rejectOrBlockContact(nxId: String, block: Boolean): Result<Unit>

    /**
     * Synchronizes and drains pending envelopes from the server, validates Ed25519 signatures,
     * verifies blocklists, and inserts REQUEST_RECEIVED records into local SQLCipher Room DB.
     */
    suspend fun syncPendingRequests(): Int

    fun getContactsByStatus(status: ContactStatus): Flow<List<ContactEntity>>

    fun getPendingCount(): Flow<Int>

    fun getAllContacts(): Flow<List<ContactEntity>>

    suspend fun getContact(nxId: String): ContactEntity?

    /**
     * Sets or clears a private user-defined custom nickname for [nxId].
     * Zero-Knowledge: This nickname is stored strictly on the local device in encrypted SQLite
     * and is never transmitted to the relay server or peer.
     */
    suspend fun setContactNickname(nxId: String, customNickname: String?): Result<Unit>

    /**
     * Performs a local encrypted search matching nickname, original profile name, or NX-ID.
     */
    fun searchContacts(query: String): Flow<List<ContactEntity>>

    /**
     * Reads device address-book contacts locally via ContentResolver, normalizes phone numbers,
     * performs authenticated batch lookup against backend for verified NEXA users,
     * and returns the augmented DeviceContact list.
     * Strict Privacy: Full address-book entries are never uploaded or persisted server-side.
     */
    suspend fun loadDeviceContacts(): Result<List<com.nexa.app.domain.model.DeviceContact>>

    /**
     * Look up a single phone number against backend to check if registered.
     */
    suspend fun lookupSinglePhone(phoneNumber: String): Result<com.nexa.app.domain.model.DeviceContact?>

    /**
     * Updates the official VEYRA / NEXA avatar URL for [nxId] upon real-time PROFILE_UPDATE.
     */
    suspend fun updateAvatarUrl(nxId: String, avatarUrl: String?): Result<Unit> = Result.success(Unit)
}
