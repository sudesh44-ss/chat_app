package com.nexa.app.data.repository

import android.content.Context
import android.util.Log
import com.nexa.app.core.dtn.BlindTokenGenerator
import com.nexa.app.core.dtn.DtnConstants
import com.nexa.app.core.dtn.DtnEnvelope
import com.nexa.app.core.security.CryptoKeystoreManager
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.data.local.dao.ChatDao
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

            // 1. Generate 16-byte Blind Token from recipient's public key & day salt
            val blindToken = BlindTokenGenerator.generateTokenFromHex(recipientPublicKeyHex)

            // 2. Wrap message into internal JSON payload before encryption
            val innerJson = JSONObject().apply {
                put("sender_nx_id", myNxId)
                put("recipient_nx_id", recipientNxId)
                put("text", plainText)
                put("timestamp", System.currentTimeMillis())
                put("packet_id", UUID.randomUUID().toString())
            }.toString()

            val plainBytes = innerJson.toByteArray(StandardCharsets.UTF_8)
            val (iv, ciphertext) = keystoreManager.encryptWithMasterKey(plainBytes)

            // Pack IV + ciphertext into envelope payload
            val encryptedPayload = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, encryptedPayload, 0, iv.size)
            System.arraycopy(ciphertext, 0, encryptedPayload, iv.size, ciphertext.size)

            // 3. Create sealed DTN envelope
            val envelope = DtnEnvelope.createNew(
                blindToken = blindToken,
                encryptedPayload = encryptedPayload,
                hopLimit = DtnConstants.DEFAULT_HOP_LIMIT,
                ttlSeconds = DtnConstants.DEFAULT_TTL_SECONDS
            )

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
        val myPkHex = user.publicKeyHex
        if (myPkHex.isBlank()) return@withContext emptyList()

        val myKeyBytes = myPkHex.hexToByteArray()
        val currentDay = BlindTokenGenerator.currentEpochDay()

        // Check tokens for today and yesterday (to tolerate clock drift across 24h boundary)
        val todayTokenHex = BlindTokenGenerator.generateToken(myKeyBytes, currentDay).toHex()
        val yesterdayTokenHex = BlindTokenGenerator.generateToken(myKeyBytes, currentDay - 1).toHex()

        val matchingRaw = mutableListOf<ByteArray>()
        matchingRaw.addAll(envelopeDao.findMatchingEnvelopes(todayTokenHex))
        matchingRaw.addAll(envelopeDao.findMatchingEnvelopes(yesterdayTokenHex))

        val consumed = mutableListOf<DtnEnvelope>()
        for (raw in matchingRaw) {
            val env = DtnEnvelope.deserialize(raw) ?: continue
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
            val myPkHex = user.publicKeyHex
            if (myPkHex.isBlank()) return false

            val myKeyBytes = myPkHex.hexToByteArray()
            val currentDay = BlindTokenGenerator.currentEpochDay()
            val todayToken = BlindTokenGenerator.generateToken(myKeyBytes, currentDay)
            val yesterdayToken = BlindTokenGenerator.generateToken(myKeyBytes, currentDay - 1)

            if (!envelope.blindToken.contentEquals(todayToken) && !envelope.blindToken.contentEquals(yesterdayToken)) {
                // Not for me -> acts strictly as a data mule
                return false
            }

            // Envelope IS for me! Decrypt the payload
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
            val text = json.getString("text")
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
            val packetId = json.optString("packet_id", UUID.randomUUID().toString())

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
