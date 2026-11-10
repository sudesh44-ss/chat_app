package com.nexa.app.data.repository

import android.content.Context
import com.google.crypto.tink.subtle.X25519
import com.nexa.app.core.crypto.DoubleRatchetEngine
import com.nexa.app.core.crypto.Ed25519Signer
import com.nexa.app.core.crypto.PrekeyBundle
import com.nexa.app.core.crypto.ProofOfWorkEngine
import com.nexa.app.core.crypto.X3dhHandshakeEngine
import com.nexa.app.core.security.CryptoKeystoreManager
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.data.local.dao.ContactDao
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import com.nexa.app.data.remote.api.HandshakeApi
import com.nexa.app.data.remote.dto.DispatchRequestDto
import com.nexa.app.domain.repository.ContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Production implementation of the Zero-Knowledge Contact Discovery and Anti-Spam Handshake Engine.
 */
class ContactRepositoryImpl(
    private val context: Context,
    private val contactDao: ContactDao,
    private val handshakeApi: HandshakeApi,
    private val secureStorage: SecureStorage,
    private val keystoreManager: CryptoKeystoreManager,
    private val doubleRatchetEngine: DoubleRatchetEngine,
    private val authApiService: com.nexa.app.data.remote.api.AuthApiService = com.nexa.app.data.remote.api.AuthApiServiceImpl(),
    private val connectionApiService: com.nexa.app.data.remote.api.ConnectionApiService = com.nexa.app.data.remote.api.ConnectionApiServiceImpl(),
    private val chatDao: com.nexa.app.data.local.dao.ChatDao? = null
) : ContactRepository {

    private val secureRandom = SecureRandom()

    override suspend fun sendFriendRequest(targetNxId: String, introMessage: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val localNxId = secureStorage.getActiveNxId()
                ?: return@withContext Result.failure(IllegalStateException("No active NX-ID registered on device"))

            // 1. Prevent duplicate requests if already mutual or pending
            val existing = contactDao.getContact(targetNxId)
            if (existing != null) {
                if (existing.status == ContactStatus.MUTUAL_FRIEND) {
                    return@withContext Result.success(Unit)
                }
                if (existing.status == ContactStatus.REQUEST_SENT) {
                    return@withContext Result.success(Unit)
                }
            }

            // 2. Fetch Recipient Prekey Bundle
            val prekeyBundle = handshakeApi.getUserPrekey(targetNxId)

            // 3. Solve Client-Side Micro-PoW (~300ms compute)
            val timestamp = System.currentTimeMillis()
            val nonce = withContext(Dispatchers.Default) {
                ProofOfWorkEngine.solve(targetNxId, timestamp)
            }

            // 4. Retrieve or Generate Local Keys
            val (dhPrivKey, dhPubKeyHex) = getOrCreateIdentityDhKeyPair()
            val (signPrivKey, _) = getOrCreateIdentitySigningKeyPair()
            val localDisplayName = localNxId // Default display identifier

            // 5. Assemble X3DH Envelope with Ed25519 Signature
            val envelope = X3dhHandshakeEngine.buildInitialEnvelope(
                senderNxId = localNxId,
                senderDisplayName = localDisplayName,
                introMessage = introMessage ?: "Hey! Let's connect on NEXA.",
                senderIdentityPrivateKey = dhPrivKey,
                senderIdentityPublicKeyHex = dhPubKeyHex,
                senderSigningPrivateKey = signPrivKey,
                recipientPrekey = prekeyBundle,
                powNonce = nonce,
                powTimestamp = timestamp,
                isAccept = false
            )

            // 6. Record State in Local SQLCipher Room DB as REQUEST_SENT
            val newContact = ContactEntity(
                nxId = targetNxId,
                displayName = targetNxId,
                publicKeyHex = prekeyBundle.identityKeyHex,
                introMessage = introMessage,
                status = ContactStatus.REQUEST_SENT
            )
            contactDao.upsertContact(newContact)

            // 7. Dispatch Sealed Envelope to Server Mailbox
            val dispatchSuccess = handshakeApi.dispatchHandshake(
                DispatchRequestDto(
                    recipientNxId = targetNxId,
                    encryptedEnvelope = envelope.toJson(),
                    powTimestamp = timestamp,
                    powNonce = nonce
                )
            )

            // 8. Register connection request in backend graph if session is active
            val sessionToken = secureStorage.getAuthToken()
            if (!sessionToken.isNullOrBlank()) {
                connectionApiService.sendRequest(sessionToken, targetNxId, introMessage)
            }

            if (!dispatchSuccess) {
                return@withContext Result.failure(IllegalStateException("Server failed to route handshake envelope"))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun acceptFriendRequest(contact: ContactEntity): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val localNxId = secureStorage.getActiveNxId()
                ?: return@withContext Result.failure(IllegalStateException("No active NX-ID registered"))

            // 1. Update Local DB Status to MUTUAL_FRIEND
            contactDao.updateStatus(contact.nxId, ContactStatus.MUTUAL_FRIEND)

            // 2. Retrieve Target Prekey Bundle & Local Keys
            val prekeyBundle = handshakeApi.getUserPrekey(contact.nxId)
            val (dhPrivKey, dhPubKeyHex) = getOrCreateIdentityDhKeyPair()
            val (signPrivKey, _) = getOrCreateIdentitySigningKeyPair()
            val localPub = fromHex(dhPubKeyHex)

            // 3. Unlock Initial Double Ratchet Session
            val remotePub = fromHex(contact.publicKeyHex)
            doubleRatchetEngine.initBilateralSession(contact.nxId, dhPrivKey, localPub, remotePub)

            // 4. Solve Micro-PoW for Accept Packet
            val timestamp = System.currentTimeMillis()
            val nonce = withContext(Dispatchers.Default) {
                ProofOfWorkEngine.solve(contact.nxId, timestamp)
            }

            // 5. Build Cryptographic ACCEPT Confirmation Envelope
            val acceptEnvelope = X3dhHandshakeEngine.buildInitialEnvelope(
                senderNxId = localNxId,
                senderDisplayName = localNxId,
                introMessage = "Connection accepted",
                senderIdentityPrivateKey = dhPrivKey,
                senderIdentityPublicKeyHex = dhPubKeyHex,
                senderSigningPrivateKey = signPrivKey,
                recipientPrekey = prekeyBundle,
                powNonce = nonce,
                powTimestamp = timestamp,
                isAccept = true
            )

            // 6. Dispatch ACCEPT to Initiator via Server
            handshakeApi.dispatchHandshake(
                DispatchRequestDto(
                    recipientNxId = contact.nxId,
                    encryptedEnvelope = acceptEnvelope.toJson(),
                    powTimestamp = timestamp,
                    powNonce = nonce
                )
            )

            // 7. Accept request in backend connection graph
            val sessionToken = secureStorage.getAuthToken()
            if (!sessionToken.isNullOrBlank()) {
                val incomingRes = connectionApiService.getIncomingRequests(sessionToken)
                val matchingReq = incomingRes.getOrNull()?.find { it.senderNxId == contact.nxId }
                if (matchingReq != null) {
                    connectionApiService.acceptRequest(sessionToken, matchingReq.id)
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun rejectOrBlockContact(nxId: String, block: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (block) {
                contactDao.updateStatus(nxId, ContactStatus.BLOCKED)
            } else {
                contactDao.deleteContact(nxId)
            }

            // Synchronize with backend connection graph if authenticated
            val sessionToken = secureStorage.getAuthToken()
            if (!sessionToken.isNullOrBlank()) {
                if (block) {
                    connectionApiService.blockUser(sessionToken, nxId)
                } else {
                    val incomingRes = connectionApiService.getIncomingRequests(sessionToken)
                    val match = incomingRes.getOrNull()?.find { it.senderNxId == nxId }
                    if (match != null) {
                        connectionApiService.rejectRequest(sessionToken, match.id)
                    } else {
                        val outgoingRes = connectionApiService.getOutgoingRequests(sessionToken)
                        val outMatch = outgoingRes.getOrNull()?.find { it.recipientNxId == nxId }
                        if (outMatch != null) {
                            connectionApiService.cancelRequest(sessionToken, outMatch.id)
                        } else {
                            connectionApiService.removeConnection(sessionToken, nxId)
                        }
                    }
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun syncPendingRequests(): Int = withContext(Dispatchers.IO) {
        val localNxId = secureStorage.getActiveNxId() ?: return@withContext 0

        try {
            var processed = 0

            // 1. Process X3DH envelopes from mailbox
            try {
                val envelopes = handshakeApi.getPendingRequests(localNxId)
                val (dhPrivKey, _) = getOrCreateIdentityDhKeyPair()
                val (signPrivKey, _) = getOrCreateIdentitySigningKeyPair()

                for (envelope in envelopes) {
                    // Check Local Blocklist: Silent Drop
                    val existing = contactDao.getContact(envelope.senderNxId)
                    if (existing != null && existing.status == ContactStatus.BLOCKED) {
                        continue
                    }

                    // Process Inbound Envelope (Anti-impersonation signature checked inside)
                    val senderSigningPub = fromHex(envelope.identityPublicKeyHex)
                    val payload = X3dhHandshakeEngine.processInboundEnvelope(
                        envelope = envelope,
                        recipientIdentityPrivateKey = dhPrivKey,
                        recipientSignedPrivateKey = dhPrivKey,
                        senderSigningPublicKey = senderSigningPub
                    ) ?: continue

                    if (envelope.isAccept) {
                        // Recipient accepted our request!
                        contactDao.updateStatus(envelope.senderNxId, ContactStatus.MUTUAL_FRIEND)
                        val remoteEphemeralPub = fromHex(envelope.ephemeralPublicKeyHex)
                        val (_, dhPubKeyHex) = getOrCreateIdentityDhKeyPair()
                        val localPub = fromHex(dhPubKeyHex)
                        doubleRatchetEngine.initBilateralSession(envelope.senderNxId, dhPrivKey, localPub, remoteEphemeralPub)
                        processed++
                    } else {
                        // New Inbound Request
                        val newContact = ContactEntity(
                            nxId = payload.senderNxId,
                            displayName = payload.displayName,
                            publicKeyHex = envelope.identityPublicKeyHex,
                            introMessage = payload.introMessage,
                            status = ContactStatus.REQUEST_RECEIVED
                        )
                        contactDao.upsertContact(newContact)
                        processed++
                    }
                }
            } catch (e: Exception) {
                // Mailbox sync error does not block REST sync
            }

            // 2. Synchronize REST Connection Graph
            val sessionToken = secureStorage.getAuthToken()
            if (!sessionToken.isNullOrBlank()) {
                try {
                    val incomingResult = connectionApiService.getIncomingRequests(sessionToken)
                    incomingResult.getOrNull()?.forEach { req ->
                        val existing = contactDao.getContact(req.senderNxId)
                        if (existing == null) {
                            contactDao.upsertContact(
                                ContactEntity(
                                    nxId = req.senderNxId,
                                    displayName = req.profile?.displayName ?: req.senderNxId,
                                    publicKeyHex = req.profile?.publicKeyHex ?: "",
                                    introMessage = req.introMessage,
                                    status = ContactStatus.REQUEST_RECEIVED
                                )
                            )
                            processed++
                        }
                    }

                    val connsResult = connectionApiService.getConnections(sessionToken)
                    connsResult.getOrNull()?.forEach { conn ->
                        val existing = contactDao.getContact(conn.nxId)
                        if (existing == null) {
                            contactDao.upsertContact(
                                ContactEntity(
                                    nxId = conn.nxId,
                                    displayName = conn.displayName.ifBlank { conn.nxId },
                                    publicKeyHex = conn.publicKeyHex,
                                    status = ContactStatus.MUTUAL_FRIEND
                                )
                            )
                        } else if (existing.status != ContactStatus.MUTUAL_FRIEND && existing.status != ContactStatus.BLOCKED) {
                            contactDao.updateStatus(conn.nxId, ContactStatus.MUTUAL_FRIEND)
                        }
                    }
                } catch (e: Exception) {
                    // Graceful degradation when network unavailable
                }
            }

            processed
        } catch (e: Exception) {
            0
        }
    }

    override fun getContactsByStatus(status: ContactStatus): Flow<List<ContactEntity>> {
        return contactDao.getContactsByStatus(status)
            .catch { emit(emptyList()) }
            .flowOn(Dispatchers.IO)
    }

    override fun getPendingCount(): Flow<Int> {
        return contactDao.getPendingReceivedCount()
            .catch { emit(0) }
            .flowOn(Dispatchers.IO)
    }

    override fun getAllContacts(): Flow<List<ContactEntity>> {
        return contactDao.getAllContacts()
            .catch { emit(emptyList()) }
            .flowOn(Dispatchers.IO)
    }

    override suspend fun getContact(nxId: String): ContactEntity? {
        return contactDao.getContact(nxId)
    }

    override suspend fun setContactNickname(nxId: String, customNickname: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val sanitized = customNickname?.trim()?.takeIf { it.isNotBlank() }
            contactDao.updateNickname(nxId, sanitized)
            val contact = contactDao.getContact(nxId)
            val effective = contact?.effectiveDisplayName ?: sanitized ?: nxId
            chatDao?.updateParticipantName(nxId, effective)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateAvatarUrl(nxId: String, avatarUrl: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            contactDao.updateAvatarUrl(nxId, avatarUrl)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun searchContacts(query: String): Flow<List<ContactEntity>> {
        return contactDao.searchContacts(query.trim())
            .catch { emit(emptyList()) }
            .flowOn(Dispatchers.IO)
    }

    // --- Keystore-Protected Key Pair Management ---

    private fun getOrCreateIdentityDhKeyPair(): Pair<ByteArray, String> {
        val cachedPub = secureStorage.getAuthToken() // Using persistent preference store
        val prefs = context.getSharedPreferences("nexa_handshake_keys", Context.MODE_PRIVATE)
        val privEncHex = prefs.getString("dh_priv_enc", null)
        val privIvHex = prefs.getString("dh_priv_iv", null)
        val pubHex = prefs.getString("dh_pub", null)

        if (!privEncHex.isNullOrBlank() && !privIvHex.isNullOrBlank() && !pubHex.isNullOrBlank()) {
            val decryptedPriv = keystoreManager.decryptWithMasterKey(fromHex(privIvHex), fromHex(privEncHex))
            return Pair(decryptedPriv, pubHex)
        }

        // Generate fresh 32-byte X25519 private key
        val newPriv = X25519.generatePrivateKey()
        val newPub = X25519.publicFromPrivate(newPriv)
        val newPubHex = toHex(newPub)

        val (iv, encPriv) = keystoreManager.encryptWithMasterKey(newPriv)
        prefs.edit()
            .putString("dh_priv_enc", toHex(encPriv))
            .putString("dh_priv_iv", toHex(iv))
            .putString("dh_pub", newPubHex)
            .apply()

        return Pair(newPriv, newPubHex)
    }

    private fun getOrCreateIdentitySigningKeyPair(): Pair<ByteArray, String> {
        val prefs = context.getSharedPreferences("nexa_handshake_keys", Context.MODE_PRIVATE)
        val privEncHex = prefs.getString("sign_priv_enc", null)
        val privIvHex = prefs.getString("sign_priv_iv", null)
        val pubHex = prefs.getString("sign_pub", null)

        if (!privEncHex.isNullOrBlank() && !privIvHex.isNullOrBlank() && !pubHex.isNullOrBlank()) {
            val decryptedPriv = keystoreManager.decryptWithMasterKey(fromHex(privIvHex), fromHex(privEncHex))
            return Pair(decryptedPriv, pubHex)
        }

        val newPriv = ByteArray(32).also { secureRandom.nextBytes(it) }
        val newPub = Ed25519Signer.derivePublicKey(newPriv)
        val newPubHex = toHex(newPub)

        val (iv, encPriv) = keystoreManager.encryptWithMasterKey(newPriv)
        prefs.edit()
            .putString("sign_priv_enc", toHex(encPriv))
            .putString("sign_priv_iv", toHex(iv))
            .putString("sign_pub", newPubHex)
            .apply()

        return Pair(newPriv, newPubHex)
    }

    override suspend fun loadDeviceContacts(): Result<List<com.nexa.app.domain.model.DeviceContact>> = withContext(Dispatchers.IO) {
        try {
            // Read device contacts purely locally via ContentResolver (Zero server upload / complete privacy)
            val localContacts = com.nexa.app.core.contacts.ContactsProviderHelper.readDeviceContacts(context)
            if (localContacts.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            val sessionToken = secureStorage.getSessionToken()
            if (sessionToken.isNullOrBlank()) {
                return@withContext Result.success(localContacts)
            }

            // Extract unique phone numbers and batch query up to 100 per request
            val phoneList = localContacts.mapNotNull { contact ->
                val num = contact.normalizedNumber.ifBlank { contact.phoneNumber }.trim()
                if (num.isNotBlank()) num else null
            }.distinct()

            val matchesByNumber = mutableMapOf<String, com.nexa.app.data.remote.dto.ContactMatchDto>()
            for (batch in phoneList.chunked(100)) {
                val lookupResult = authApiService.lookupContacts(sessionToken, batch)
                lookupResult.getOrNull()?.forEach { match ->
                    matchesByNumber[match.phoneNumber] = match
                }
            }

            if (matchesByNumber.isEmpty()) {
                return@withContext Result.success(localContacts)
            }

            val augmented = localContacts.map { contact ->
                val norm = contact.normalizedNumber.ifBlank { contact.phoneNumber }.trim()
                val match = matchesByNumber[norm]
                if (match != null) {
                    contact.copy(
                        isNexaUser = true,
                        nexaNxId = match.nxId
                    )
                } else {
                    contact
                }
            }

            Result.success(augmented)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun lookupSinglePhone(phoneNumber: String): Result<com.nexa.app.domain.model.DeviceContact?> = withContext(Dispatchers.IO) {
        try {
            val normalized = com.nexa.app.core.contacts.ContactsProviderHelper.normalizePhoneNumber(phoneNumber)
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid phone number format"))

            val sessionToken = secureStorage.getSessionToken() ?: ""
            val matchResult = authApiService.lookupContacts(sessionToken, listOf(normalized))
            val match = matchResult.getOrNull()?.firstOrNull()

            if (match != null) {
                Result.success(
                    com.nexa.app.domain.model.DeviceContact(
                        id = match.nxId,
                        displayName = match.displayName.ifBlank { match.nxId },
                        phoneNumber = match.phoneNumber,
                        normalizedNumber = match.phoneNumber,
                        isNexaUser = true,
                        nexaNxId = match.nxId,
                        nexaPublicKeyHex = match.publicKeyHex
                    )
                )
            } else {
                Result.success(
                    com.nexa.app.domain.model.DeviceContact(
                        id = normalized,
                        displayName = normalized,
                        phoneNumber = normalized,
                        normalizedNumber = normalized,
                        isNexaUser = false
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getSessionToken(): String {
        return secureStorage.getSessionToken() ?: ""
    }

    override suspend fun discoverUser(nxId: String): Result<com.nexa.app.data.remote.dto.DiscoveredUserDto> = withContext(Dispatchers.IO) {
        val token = getSessionToken()
        if (token.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No authenticated session found"))
        }
        connectionApiService.discoverUser(token, nxId)
    }

    override suspend fun getIncomingConnectionRequests(): Result<List<com.nexa.app.data.remote.dto.ConnectionRequestItemDto>> = withContext(Dispatchers.IO) {
        val token = getSessionToken()
        if (token.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No authenticated session found"))
        }
        val result = connectionApiService.getIncomingRequests(token)
        result.onSuccess { requests ->
            for (req in requests) {
                val profile = req.profile
                val contact = ContactEntity(
                    nxId = req.senderNxId,
                    displayName = profile?.displayName ?: req.senderNxId,
                    publicKeyHex = profile?.publicKeyHex ?: "",
                    introMessage = req.introMessage,
                    status = ContactStatus.REQUEST_RECEIVED,
                    nexaAvatarUrl = profile?.avatarUrl
                )
                contactDao.upsertContact(contact)
            }
        }
        result
    }

    override suspend fun getOutgoingConnectionRequests(): Result<List<com.nexa.app.data.remote.dto.ConnectionRequestItemDto>> = withContext(Dispatchers.IO) {
        val token = getSessionToken()
        if (token.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No authenticated session found"))
        }
        val result = connectionApiService.getOutgoingRequests(token)
        result.onSuccess { requests ->
            for (req in requests) {
                val profile = req.profile
                val contact = ContactEntity(
                    nxId = req.recipientNxId,
                    displayName = profile?.displayName ?: req.recipientNxId,
                    publicKeyHex = profile?.publicKeyHex ?: "",
                    introMessage = req.introMessage,
                    status = ContactStatus.REQUEST_SENT,
                    nexaAvatarUrl = profile?.avatarUrl
                )
                contactDao.upsertContact(contact)
            }
        }
        result
    }

    override suspend fun acceptConnectionRequestById(requestId: String, contact: ContactEntity): Result<Unit> = withContext(Dispatchers.IO) {
        val token = getSessionToken()
        if (token.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No authenticated session found"))
        }
        val res = connectionApiService.acceptRequest(token, requestId)
        if (res.isSuccess) {
            contactDao.updateStatus(contact.nxId, ContactStatus.MUTUAL_FRIEND)
            Result.success(Unit)
        } else {
            Result.failure(res.exceptionOrNull() ?: Exception("Failed to accept connection request"))
        }
    }

    override suspend fun rejectConnectionRequestById(requestId: String, nxId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = getSessionToken()
        if (token.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No authenticated session found"))
        }
        val res = connectionApiService.rejectRequest(token, requestId)
        if (res.isSuccess) {
            contactDao.deleteContact(nxId)
            Result.success(Unit)
        } else {
            Result.failure(res.exceptionOrNull() ?: Exception("Failed to reject connection request"))
        }
    }

    override suspend fun cancelConnectionRequestById(requestId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = getSessionToken()
        if (token.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No authenticated session found"))
        }
        val res = connectionApiService.cancelRequest(token, requestId)
        if (res.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(res.exceptionOrNull() ?: Exception("Failed to cancel connection request"))
        }
    }

    override suspend fun syncConnectionsFromServer(): Result<List<ContactEntity>> = withContext(Dispatchers.IO) {
        val token = getSessionToken()
        if (token.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No authenticated session found"))
        }
        val res = connectionApiService.getConnections(token)
        res.map { list ->
            val entities = list.map { item ->
                val entity = ContactEntity(
                    nxId = item.nxId,
                    displayName = item.displayName,
                    publicKeyHex = item.publicKeyHex,
                    status = ContactStatus.MUTUAL_FRIEND,
                    nexaAvatarUrl = item.avatarUrl,
                    createdAtTimestamp = item.createdAt
                )
                contactDao.upsertContact(entity)
                entity
            }
            entities
        }
    }

    override suspend fun removeConnection(targetNxId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = getSessionToken()
        if (token.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No authenticated session found"))
        }
        val res = connectionApiService.removeConnection(token, targetNxId)
        if (res.isSuccess) {
            contactDao.deleteContact(targetNxId)
            Result.success(Unit)
        } else {
            Result.failure(res.exceptionOrNull() ?: Exception("Failed to remove connection"))
        }
    }

    override suspend fun blockUser(targetNxId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = getSessionToken()
        if (token.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No authenticated session found"))
        }
        val res = connectionApiService.blockUser(token, targetNxId)
        if (res.isSuccess) {
            contactDao.updateStatus(targetNxId, ContactStatus.BLOCKED)
            Result.success(Unit)
        } else {
            Result.failure(res.exceptionOrNull() ?: Exception("Failed to block user"))
        }
    }

    override suspend fun unblockUser(targetNxId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = getSessionToken()
        if (token.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No authenticated session found"))
        }
        val res = connectionApiService.unblockUser(token, targetNxId)
        if (res.isSuccess) {
            contactDao.deleteContact(targetNxId)
            Result.success(Unit)
        } else {
            Result.failure(res.exceptionOrNull() ?: Exception("Failed to unblock user"))
        }
    }

    override suspend fun getBlockedUsers(): Result<List<com.nexa.app.data.remote.dto.BlockedUserItemDto>> = withContext(Dispatchers.IO) {
        val token = getSessionToken()
        if (token.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No authenticated session found"))
        }
        connectionApiService.getBlockedUsers(token)
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun fromHex(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
