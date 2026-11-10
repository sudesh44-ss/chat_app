package com.nexa.app.data.repository

import android.content.Context
import android.util.Log
import com.nexa.app.core.dtn.BlindTokenGenerator
import com.nexa.app.core.dtn.DtnConstants
import com.nexa.app.core.dtn.DtnEnvelope
import com.nexa.app.core.dtn.PairwiseRoutingTokenGenerator
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.core.proto.PacketType
import com.nexa.app.core.security.CryptoKeystoreManager
import com.nexa.app.core.crypto.DoubleRatchetEngine
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.core.transport.NexaUniversalTransportIngest
import com.nexa.app.core.transport.TransportMedium
import com.nexa.app.data.local.dao.ChatDao
import com.nexa.app.data.local.dao.ContactDao
import com.nexa.app.data.local.dao.getAllContactsList
import com.nexa.app.data.local.dao.DtnEnvelopeDao
import com.nexa.app.data.local.dao.MessageDao
import com.nexa.app.data.local.dao.UserDao
import com.nexa.app.data.local.entity.ChatEntity
import com.nexa.app.data.local.entity.DtnEnvelopeEntity
import com.nexa.app.data.local.entity.MessageEntity
import com.nexa.app.data.remote.api.DtnRelayApi
import com.nexa.app.data.remote.dtn.DtnSyncWorker
import com.nexa.app.data.remote.dtn.MeshState
import com.nexa.app.data.remote.dtn.NearbyMeshManager
import com.nexa.app.domain.model.MessageStatus
import com.nexa.app.domain.model.TransportType
import com.nexa.app.domain.repository.DtnRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

class DtnRepositoryImpl(
    private val context: Context,
    private val envelopeDao: DtnEnvelopeDao,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val userDao: UserDao,
    private val secureStorage: SecureStorage,
    private val keystoreManager: CryptoKeystoreManager,
    private val relayApi: DtnRelayApi
) : DtnRepository {

    var doubleRatchetEngine: DoubleRatchetEngine? = null
    var universalTransportIngest: NexaUniversalTransportIngest? = null
    var contactDao: ContactDao? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val meshManager: NearbyMeshManager = NearbyMeshManager(
        context = context,
        envelopeDao = envelopeDao,
        onEnvelopeReceivedForMe = { envelope ->
            inspectAndProcessInboundEnvelope(envelope)
        }
    )

    override val meshState: StateFlow<MeshState>
        get() = meshManager.meshState

    override val heldEnvelopeCount: Flow<Int>
        get() = envelopeDao.getCachedCountFlow()

    override fun startMesh() {
        meshManager.start()
    }

    override fun stopMesh() {
        meshManager.stop()
    }

    override fun whitelistMeshPeer(peerId: String) {
        meshManager.whitelistPeer(peerId)
    }

    override suspend fun createAndCacheEnvelope(
        recipientNxId: String,
        recipientPublicKeyHex: String,
        plainText: String
    ): Result<DtnEnvelope> = withContext(Dispatchers.IO) {
        try {
            val myNxId = secureStorage.getActiveNxId() ?: "NX-ANONYMOUS"

            // 1. Generate 16-byte Pairwise Rotating Routing Token (Phase C) with fallback
            val blindToken = try {
                val dhKeys = keystoreManager.getOrCreateDhIdentityKeyPair(secureStorage)
                val myPriv = keystoreManager.decryptWithMasterKey(dhKeys.iv, dhKeys.encryptedPrivateKey)
                val recipientPubBytes = recipientPublicKeyHex.hexToByteArray()
                if (recipientPubBytes.size == 32) {
                    val pairwiseSecret = PairwiseRoutingTokenGenerator.derivePairwiseDhSecret(myPriv, recipientPubBytes)
                    val routingKey = PairwiseRoutingTokenGenerator.deriveRoutingKey(pairwiseSecret, myNxId, recipientNxId)
                    val token = PairwiseRoutingTokenGenerator.generateRoutingToken(routingKey)
                    java.util.Arrays.fill(myPriv, 0.toByte())
                    java.util.Arrays.fill(pairwiseSecret, 0.toByte())
                    token
                } else {
                    BlindTokenGenerator.generateTokenFromHex(recipientPublicKeyHex)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falling back to legacy blind token: ${e.message}")
                BlindTokenGenerator.generateTokenFromHex(recipientPublicKeyHex)
            }

            // 2. Encrypt using Double Ratchet E2EE if engine is available
            val encryptedPayload = if (doubleRatchetEngine != null) {
                val plainBytes = plainText.toByteArray(StandardCharsets.UTF_8)
                val recipientPubKeyBytes = recipientPublicKeyHex.hexToByteArray()
                val encEnvelope = doubleRatchetEngine?.ratchetEncrypt(recipientNxId, plainBytes, recipientPubKeyBytes)
                    ?: throw IllegalStateException("Double Ratchet encryption returned null")

                val wirePacket = NexaWirePacket(
                    packetId = UUID.randomUUID().toString(),
                    type = PacketType.PACKET_MESSAGE,
                    senderNxId = myNxId,
                    recipientNxId = recipientNxId,
                    timestamp = System.currentTimeMillis(),
                    message = encEnvelope.toProtobufPayload()
                )
                wirePacket.encode()
            } else {
                // Fallback to Keystore encryption for test environments without ratchet
                val innerJson = JSONObject().apply {
                    put("sender_nx_id", myNxId)
                    put("recipient_nx_id", recipientNxId)
                    put("text", plainText)
                    put("timestamp", System.currentTimeMillis())
                    put("packet_id", UUID.randomUUID().toString())
                }.toString()

                val plainBytes = innerJson.toByteArray(StandardCharsets.UTF_8)
                val (iv, ciphertext) = keystoreManager.encryptWithMasterKey(plainBytes)

                val payload = ByteArray(iv.size + ciphertext.size)
                System.arraycopy(iv, 0, payload, 0, iv.size)
                System.arraycopy(ciphertext, 0, payload, iv.size, ciphertext.size)
                payload
            }

            // 3. Create cryptographically authenticated NXDT envelope (Fail-closed on missing key)
            val idKeyPair = try {
                keystoreManager.getOrCreateIdentityKeyPair(secureStorage)
            } catch (e: Exception) {
                null
            } ?: throw IllegalStateException("Cannot create authenticated DTN envelope: Device identity key unavailable")

            val envelope = keystoreManager.withDecryptedPrivateKey(idKeyPair) { rawPriv ->
                val pubBytes = idKeyPair.publicKeyHex.hexToByteArray()
                DtnEnvelope.createAuthenticated(
                    blindToken = blindToken,
                    encryptedPayload = encryptedPayload,
                    senderPrivateKey = rawPriv,
                    senderPublicKey = pubBytes,
                    hopLimit = DtnConstants.DEFAULT_HOP_LIMIT,
                    ttlSeconds = DtnConstants.DEFAULT_TTL_SECONDS
                )
            }

            // 4. Persist to local SQLCipher DTN vault
            val entity = DtnEnvelopeEntity(
                envelopeIdHex = envelope.envelopeIdHex,
                blindTokenHex = envelope.blindTokenHex,
                hopLimit = envelope.hopLimit.toInt(),
                rawEnvelopeBytes = envelope.serialize(),
                expiresAtEpochSec = envelope.expiresAtEpochSec
            )
            envelopeDao.insertEnvelope(entity)

            // 5. Schedule background opportunistic dump constraint
            DtnSyncWorker.scheduleOpportunisticDump(context)

            Log.i(TAG, "Created & vaulted DTN envelope [${envelope.envelopeIdHex.take(8)}...] for recipient $recipientNxId")
            Result.success(envelope)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create DTN envelope", e)
            Result.failure(e)
        }
    }

    override suspend fun ingestInboundEnvelope(envelope: DtnEnvelope): Boolean = withContext(Dispatchers.IO) {
        if (envelope.hopLimit <= 0) return@withContext false

        val nowSec = System.currentTimeMillis() / 1000L
        if (envelope.expiresAtEpochSec <= nowSec) return@withContext false

        // Cryptographic integrity gate: Reject invalid or tampered NXDT v1 envelopes
        if (envelope.protocolVersion == DtnConstants.PROTOCOL_VERSION_1 && !envelope.verifySignature()) {
            Log.w(TAG, "Rejected inbound NXDT envelope [${envelope.envelopeIdHex.take(8)}]: Signature verification failed")
            return@withContext false
        }

        val entity = DtnEnvelopeEntity(
            envelopeIdHex = envelope.envelopeIdHex,
            blindTokenHex = envelope.blindTokenHex,
            hopLimit = envelope.hopLimit.toInt(),
            rawEnvelopeBytes = envelope.serialize(),
            expiresAtEpochSec = envelope.expiresAtEpochSec
        )
        val rowId = envelopeDao.insertEnvelope(entity)
        if (rowId > 0) {
            inspectAndProcessInboundEnvelope(envelope)
            true
        } else {
            false
        }
    }

    override suspend fun checkAndConsumeEnvelopesForMe(): List<DtnEnvelope> = withContext(Dispatchers.IO) {
        val user = userDao.getLocalProfile() ?: return@withContext emptyList()
        val myNxId = user.nxId
        val myPkHex = user.publicKeyHex

        val tokenHexesToSearch = mutableSetOf<String>()

        // 1. Pairwise Rotating Routing Tokens across established contacts (Phase C)
        val contacts = contactDao?.getAllContactsList() ?: emptyList()
        val currentEpoch = PairwiseRoutingTokenGenerator.currentEpoch()
        if (contacts.isNotEmpty()) {
            try {
                val dhKeys = keystoreManager.getOrCreateDhIdentityKeyPair(secureStorage)
                val myPriv = keystoreManager.decryptWithMasterKey(dhKeys.iv, dhKeys.encryptedPrivateKey)
                for (contact in contacts) {
                    if (contact.publicKeyHex.length == 64) {
                        val contactPub = contact.publicKeyHex.hexToByteArray()
                        val pairwiseSecret = PairwiseRoutingTokenGenerator.derivePairwiseDhSecret(myPriv, contactPub)
                        val routingKey = PairwiseRoutingTokenGenerator.deriveRoutingKey(pairwiseSecret, contact.nxId, myNxId)
                        tokenHexesToSearch.addAll(PairwiseRoutingTokenGenerator.getActiveInboundTokenHexes(routingKey, currentEpoch))
                        java.util.Arrays.fill(pairwiseSecret, 0.toByte())
                    }
                }
                java.util.Arrays.fill(myPriv, 0.toByte())
            } catch (e: Exception) {
                Log.w(TAG, "Error deriving pairwise routing tokens: ${e.message}")
            }
        }

        // 2. Legacy fallback tokens (today and yesterday)
        if (myPkHex.isNotBlank()) {
            val myKeyBytes = myPkHex.hexToByteArray()
            val currentDay = BlindTokenGenerator.currentEpochDay()
            tokenHexesToSearch.add(BlindTokenGenerator.generateToken(myKeyBytes, currentDay).toHex())
            tokenHexesToSearch.add(BlindTokenGenerator.generateToken(myKeyBytes, currentDay - 1).toHex())
        }

        val matchingRaw = if (tokenHexesToSearch.isNotEmpty()) {
            envelopeDao.findMatchingEnvelopesBatch(tokenHexesToSearch.toList())
        } else {
            emptyList()
        }

        val consumed = mutableListOf<DtnEnvelope>()
        for (raw in matchingRaw) {
            val env = DtnEnvelope.deserialize(raw) ?: continue
            if (env.protocolVersion == DtnConstants.PROTOCOL_VERSION_1 && !env.verifySignature()) {
                Log.w(TAG, "Discarded tampered cached NXDT envelope [${env.envelopeIdHex.take(8)}]")
                envelopeDao.deleteEnvelope(env.envelopeIdHex)
                continue
            }
            if (inspectAndProcessInboundEnvelope(env)) {
                consumed.add(env)
                envelopeDao.deleteEnvelope(env.envelopeIdHex)
            }
        }

        consumed
    }

    override suspend fun triggerOpportunisticDump(): Result<Int> = withContext(Dispatchers.IO) {
        val nowSec = System.currentTimeMillis() / 1000L
        envelopeDao.purgeExpiredEnvelopes(nowSec)
        val pending = envelopeDao.getActiveEnvelopesForSync(nowSec)
        if (pending.isEmpty()) return@withContext Result.success(0)

        val batch = pending.map { it.rawEnvelopeBytes }
        val result = relayApi.dumpOpportunisticEnvelopes(batch)
        if (result.isSuccess) {
            for (item in pending) {
                envelopeDao.deleteEnvelope(item.envelopeIdHex)
            }
        }
        result
    }

    private suspend fun inspectAndProcessInboundEnvelope(envelope: DtnEnvelope): Boolean {
        return try {
            val user = userDao.getLocalProfile() ?: return false
            val myNxId = user.nxId
            var isForMe = false

            // 1. Check Pairwise Rotating Routing Tokens (Phase C)
            val contacts = contactDao?.getAllContactsList() ?: emptyList()
            var matchedContact: com.nexa.app.data.local.entity.ContactEntity? = null
            if (contacts.isNotEmpty()) {
                try {
                    val dhKeys = keystoreManager.getOrCreateDhIdentityKeyPair(secureStorage)
                    val myPriv = keystoreManager.decryptWithMasterKey(dhKeys.iv, dhKeys.encryptedPrivateKey)
                    val nowSec = System.currentTimeMillis() / 1000L
                    for (contact in contacts) {
                        if (contact.publicKeyHex.length == 64) {
                            val contactPub = contact.publicKeyHex.hexToByteArray()
                            val pairwiseSecret = PairwiseRoutingTokenGenerator.derivePairwiseDhSecret(myPriv, contactPub)
                            val routingKey = PairwiseRoutingTokenGenerator.deriveRoutingKey(pairwiseSecret, contact.nxId, myNxId)
                            if (PairwiseRoutingTokenGenerator.isTokenInWindowForEnvelope(
                                    candidateToken = envelope.blindToken,
                                    routingKey = routingKey,
                                    createdAtEpochSec = envelope.createdAtEpochSec,
                                    expiresAtEpochSec = envelope.expiresAtEpochSec,
                                    nowSec = nowSec
                                )
                            ) {
                                isForMe = true
                                matchedContact = contact
                                java.util.Arrays.fill(pairwiseSecret, 0.toByte())
                                break
                            }
                            java.util.Arrays.fill(pairwiseSecret, 0.toByte())
                        }
                    }
                    java.util.Arrays.fill(myPriv, 0.toByte())
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking pairwise tokens for inbound envelope: ${e.message}")
                }
            }

            // 2. Legacy fallback
            if (!isForMe && user.publicKeyHex.isNotBlank()) {
                val myKeyBytes = user.publicKeyHex.hexToByteArray()
                val currentDay = BlindTokenGenerator.currentEpochDay()
                val todayToken = BlindTokenGenerator.generateToken(myKeyBytes, currentDay)
                val yesterdayToken = BlindTokenGenerator.generateToken(myKeyBytes, currentDay - 1)
                if (envelope.blindToken.contentEquals(todayToken) || envelope.blindToken.contentEquals(yesterdayToken)) {
                    isForMe = true
                }
            }

            if (!isForMe) {
                // Not for me -> acts strictly as a data mule
                return false
            }

            // Authenticate Sender Identity before delivery (Phase B Security Enforcement)
            if (envelope.protocolVersion == DtnConstants.PROTOCOL_VERSION_1) {
                val senderPub = envelope.senderPublicKey
                if (senderPub == null || senderPub.size != 32) {
                    Log.w(TAG, "Security Rejection: Inbound NXDT envelope [${envelope.envelopeIdHex.take(8)}] missing senderPublicKey")
                    return false
                }
                // Verify sender public key against matched contact identity if available
                if (matchedContact != null && matchedContact.publicKeyHex.length == 64) {
                    if (!senderPub.toHex().equals(matchedContact.publicKeyHex, ignoreCase = true)) {
                        Log.e(
                            TAG,
                            "SECURITY ALERT: Inbound envelope sender key mismatch! Claimed routing peer: ${matchedContact.nxId}. Dropping spoofed envelope."
                        )
                        return false
                    }
                }
            }

            // Envelope IS for me! Ingest via Universal Ingest if available
            if (universalTransportIngest != null) {
                val ingested = universalTransportIngest?.ingestRawBytes(
                    envelope.encryptedPayload,
                    TransportMedium.OFFLINE_MESH
                ) ?: false
                if (ingested) {
                    envelopeDao.deleteEnvelope(envelope.envelopeIdHex)
                    Log.i(TAG, "Ingested and unvaulted DTN message [${envelope.envelopeIdHex.take(8)}] via Universal Ingest")
                    return true
                }
            }

            // Fallback for legacy format or standalone tests
            val payload = envelope.encryptedPayload
            if (payload.size <= 12) return false

            val iv = ByteArray(12)
            val ciphertext = ByteArray(payload.size - 12)
            System.arraycopy(payload, 0, iv, 0, 12)
            System.arraycopy(payload, 12, ciphertext, 0, ciphertext.size)

            val decryptedBytes = keystoreManager.decryptWithMasterKey(iv, ciphertext)
            val decryptedJsonStr = String(decryptedBytes, StandardCharsets.UTF_8)
            val json = JSONObject(decryptedJsonStr)

            val senderNxId = json.getString("sender_nx_id")
            val recipientNxId = json.getString("recipient_nx_id")
            if (recipientNxId != myNxId) {
                Log.w(TAG, "Security Rejection: Inbound message recipient $recipientNxId does not match local user $myNxId")
                return false
            }

            // Sender identity verification for fallback path
            if (envelope.protocolVersion == DtnConstants.PROTOCOL_VERSION_1) {
                val contact = contactDao?.getContact(senderNxId)
                if (contact != null && contact.publicKeyHex.length == 64) {
                    val senderPubHex = envelope.senderPublicKey?.toHex() ?: ""
                    if (!senderPubHex.equals(contact.publicKeyHex, ignoreCase = true)) {
                        Log.e(TAG, "SECURITY ALERT: Fallback path rejected spoofed sender identity for $senderNxId")
                        return false
                    }
                }
            }
            val text = json.getString("text")
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
            val packetId = json.optString("packet_id", UUID.randomUUID().toString())

            // Persistent deduplication gate check
            if (messageDao.getMessageByPacketId(packetId) != null) {
                envelopeDao.deleteEnvelope(envelope.envelopeIdHex)
                return true
            }

            val conversationId = deriveConversationId(senderNxId, recipientNxId)

            // Insert into MessageDao as DELIVERED via DTN_MESH
            val messageEntity = MessageEntity(
                packetId = packetId,
                conversationId = conversationId,
                senderNxId = senderNxId,
                recipientNxId = recipientNxId,
                plainTextContent = text,
                status = MessageStatus.DELIVERED.name,
                timestamp = timestamp,
                isOutgoing = false,
                transportType = TransportType.DTN_MESH.name
            )
            messageDao.insertMessage(messageEntity)

            // Update chat conversation
            val existingChat = chatDao.getChatById(conversationId)
            val unread = (existingChat?.unreadCount ?: 0) + 1
            chatDao.upsertChat(
                ChatEntity(
                    conversationId = conversationId,
                    participantNxId = senderNxId,
                    participantName = existingChat?.participantName ?: senderNxId,
                    lastMessageText = text,
                    lastMessageTimestamp = timestamp,
                    unreadCount = unread
                )
            )

            // Remove from DTN vault since it reached the target destination
            envelopeDao.deleteEnvelope(envelope.envelopeIdHex)
            Log.i(TAG, "Unblinded and consumed DTN message $packetId from $senderNxId delivered via Mesh")
            true
        } catch (e: Exception) {
            // Decryption failure means this mule is not the intended recipient
            false
        }
    }

    private fun deriveConversationId(id1: String, id2: String): String {
        return if (id1 < id2) "${id1}_$id2" else "${id2}_$id1"
    }

    private fun ByteArray.toHex(): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(size * 2)
        for (b in this) {
            val i = b.toInt() and 0xFF
            result.append(hexChars[i ushr 4])
            result.append(hexChars[i and 0x0F])
        }
        return result.toString()
    }

    private fun String.hexToByteArray(): ByteArray {
        val len = length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(this[i], 16) shl 4) +
                    Character.digit(this[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    companion object {
        private const val TAG = "DtnRepositoryImpl"
    }
}
