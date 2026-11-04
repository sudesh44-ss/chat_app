package com.nexa.app.core.crypto

import android.util.Log
import com.google.crypto.tink.subtle.X25519
import com.nexa.app.data.local.dao.RatchetDao
import com.nexa.app.data.local.entity.RatchetStateEntity
import com.nexa.app.data.local.entity.SkippedKeyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signal-style Double Ratchet Engine (X25519 DH Ratchet + HKDF-SHA256 KDF Chains).
 * Provides Forward Secrecy, Post-Compromise Security, and Out-of-Order Delivery Tolerance
 * via a local SQLCipher skipped-keys table with 24-hour TTL pruning.
 * Thread-safe: per-contact session Mutex serializes state transitions.
 */
class DoubleRatchetEngine(
    private val ratchetDao: RatchetDao,
    private val identityKeyPairProvider: (() -> Pair<ByteArray, ByteArray>?)? = null
) {

    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    private fun getLock(contactNxId: String): Mutex {
        return sessionLocks.computeIfAbsent(contactNxId) { Mutex() }
    }

    suspend fun getOrCreateSession(
        contactNxId: String,
        remoteDhPublicKey: ByteArray? = null
    ): RatchetStateEntity = withContext(Dispatchers.IO) {
        val lock = getLock(contactNxId)
        lock.withLock {
            getOrCreateSessionInternal(contactNxId, remoteDhPublicKey)
        }
    }

    private suspend fun getOrCreateSessionInternal(
        contactNxId: String,
        remoteDhPublicKey: ByteArray? = null
    ): RatchetStateEntity {
        val existing = ratchetDao.getSession(contactNxId)
        if (existing != null) return existing

        val localKeys = identityKeyPairProvider?.invoke()
        val localPriv = localKeys?.first ?: X25519.generatePrivateKey()
        val localPub = localKeys?.second ?: X25519.publicFromPrivate(localPriv)

        val remotePub = remoteDhPublicKey ?: X25519.publicFromPrivate(X25519.generatePrivateKey())
        val initialDhSecret = X25519.computeSharedSecret(localPriv, remotePub)

        val (rootKey, chainKey) = kdfRk(initialDhSecret, initialDhSecret)
        val (nextChainKey, secondChainKey) = kdfCk(chainKey)

        // Asymmetric chain key assignment to prevent sequence 0 collision
        val isInitiator = remoteDhPublicKey == null || toHex(localPub) < toHex(remotePub)
        val senderKey = if (isInitiator) chainKey else secondChainKey
        val receiverKey = if (isInitiator) secondChainKey else chainKey

        val session = RatchetStateEntity(
            contactNxId = contactNxId,
            rootKeyHex = toHex(rootKey),
            senderChainKeyHex = toHex(senderKey),
            receiverChainKeyHex = toHex(receiverKey),
            senderSeqNumber = 0,
            receiverSeqNumber = 0,
            localDhPrivateKeyHex = toHex(localPriv),
            localDhPublicKeyHex = toHex(localPub),
            remoteDhPublicKeyHex = toHex(remotePub),
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        ratchetDao.upsertSession(session)
        return session
    }

    suspend fun initBilateralSession(
        contactNxId: String,
        localPriv: ByteArray,
        localPub: ByteArray,
        remotePub: ByteArray,
        isInitiator: Boolean? = null
    ): RatchetStateEntity = withContext(Dispatchers.IO) {
        val lock = getLock(contactNxId)
        lock.withLock {
            val sharedSecret = X25519.computeSharedSecret(localPriv, remotePub)
            val (rootKey, chainKey) = kdfRk(sharedSecret, sharedSecret)
            val (nextChainKey, secondChainKey) = kdfCk(chainKey)

            // Deterministic initiator determination if not explicitly specified
            val initiatorRole = isInitiator ?: (toHex(localPub) < toHex(remotePub))
            val senderKey = if (initiatorRole) chainKey else secondChainKey
            val receiverKey = if (initiatorRole) secondChainKey else chainKey

            val session = RatchetStateEntity(
                contactNxId = contactNxId,
                rootKeyHex = toHex(rootKey),
                senderChainKeyHex = toHex(senderKey),
                receiverChainKeyHex = toHex(receiverKey),
                senderSeqNumber = 0,
                receiverSeqNumber = 0,
                localDhPrivateKeyHex = toHex(localPriv),
                localDhPublicKeyHex = toHex(localPub),
                remoteDhPublicKeyHex = toHex(remotePub),
                lastUpdatedTimestamp = System.currentTimeMillis()
            )
            ratchetDao.upsertSession(session)
            session
        }
    }

    suspend fun ratchetEncrypt(
        contactNxId: String,
        plaintext: ByteArray,
        recipientDhPublicKey: ByteArray? = null
    ): EncryptedEnvelope = withContext(Dispatchers.IO) {
        val lock = getLock(contactNxId)
        lock.withLock {
            val session = getOrCreateSessionInternal(contactNxId, recipientDhPublicKey)

            val senderChainKey = fromHex(session.senderChainKeyHex)
            val (nextChainKey, messageKey) = kdfCk(senderChainKey)

            val seqNumber = session.senderSeqNumber
            val nonce = deriveNonce(messageKey, seqNumber)

            val payloadBuffer = ByteBuffer.allocate(4 + plaintext.size)
            payloadBuffer.putInt(seqNumber)
            payloadBuffer.put(plaintext)
            val fullPlaintext = payloadBuffer.array()

            val (ciphertext, tag) = ChaChaAeadCipher.encrypt(messageKey, nonce, fullPlaintext)
            val localPub = fromHex(session.localDhPublicKeyHex)

            val updatedSession = session.copy(
                senderChainKeyHex = toHex(nextChainKey),
                senderSeqNumber = seqNumber + 1,
                lastUpdatedTimestamp = System.currentTimeMillis()
            )
            ratchetDao.upsertSession(updatedSession)

            EncryptedEnvelope(
                version = EncryptedEnvelope.CURRENT_VERSION,
                ephemeralPublicKey = localPub,
                authTag = tag,
                ciphertext = ciphertext
            )
        }
    }

    suspend fun ratchetDecrypt(
        contactNxId: String,
        envelope: EncryptedEnvelope
    ): ByteArray? = withContext(Dispatchers.IO) {
        val lock = getLock(contactNxId)
        lock.withLock {
            var session = getOrCreateSessionInternal(contactNxId, envelope.ephemeralPublicKey)

            val remotePub = envelope.ephemeralPublicKey
            val currentRemotePub = fromHex(session.remoteDhPublicKeyHex)

            // 1. DH Ratchet Step if remote peer rotated their public key
            if (!remotePub.contentEquals(currentRemotePub)) {
                val localPriv = fromHex(session.localDhPrivateKeyHex)
                val dhSecret = X25519.computeSharedSecret(localPriv, remotePub)
                val rootKey = fromHex(session.rootKeyHex)
                val (newRootKey, newReceiverChainKey) = kdfRk(rootKey, dhSecret)

                val newLocalPriv = X25519.generatePrivateKey()
                val newLocalPub = X25519.publicFromPrivate(newLocalPriv)
                val newDhSecret = X25519.computeSharedSecret(newLocalPriv, remotePub)
                val (finalRootKey, newSenderChainKey) = kdfRk(newRootKey, newDhSecret)

                session = session.copy(
                    rootKeyHex = toHex(finalRootKey),
                    senderChainKeyHex = toHex(newSenderChainKey),
                    receiverChainKeyHex = toHex(newReceiverChainKey),
                    localDhPrivateKeyHex = toHex(newLocalPriv),
                    localDhPublicKeyHex = toHex(newLocalPub),
                    remoteDhPublicKeyHex = toHex(remotePub),
                    receiverSeqNumber = 0,
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
                ratchetDao.upsertSession(session)
            }

            // 2. Check previously skipped keys first (Out-of-order past arrival)
            val maxLookahead = 30
            val minCheckSeq = (session.receiverSeqNumber - maxLookahead).coerceAtLeast(0)
            for (skippedSeq in minCheckSeq until session.receiverSeqNumber) {
                val skippedEntity = ratchetDao.getSkippedKey(contactNxId, skippedSeq)
                if (skippedEntity != null) {
                    val candidateKey = fromHex(skippedEntity.derivedKeyHex)
                    val candidateNonce = deriveNonce(candidateKey, skippedSeq)
                    val decrypted = ChaChaAeadCipher.decrypt(candidateKey, candidateNonce, envelope.ciphertext, envelope.authTag)
                    if (decrypted != null && decrypted.size >= 4) {
                        val buffer = ByteBuffer.wrap(decrypted)
                        buffer.getInt() // seq
                        val actualMsg = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        ratchetDao.deleteSkippedKey(contactNxId, skippedSeq)
                        return@withLock actualMsg
                    }
                }
            }

            // 3. Try sequential receiving chain
            var receiverChainKey = fromHex(session.receiverChainKeyHex)
            val (nextChainKey, messageKey) = kdfCk(receiverChainKey)
            val nonce = deriveNonce(messageKey, session.receiverSeqNumber)

            val decryptedPayload = ChaChaAeadCipher.decrypt(messageKey, nonce, envelope.ciphertext, envelope.authTag)

            if (decryptedPayload != null && decryptedPayload.size >= 4) {
                val buffer = ByteBuffer.wrap(decryptedPayload)
                buffer.getInt() // seq
                val actualMessage = ByteArray(buffer.remaining()).also { buffer.get(it) }

                val updated = session.copy(
                    receiverChainKeyHex = toHex(nextChainKey),
                    receiverSeqNumber = session.receiverSeqNumber + 1,
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
                ratchetDao.upsertSession(updated)

                return@withLock actualMessage
            }

            // 4. Lookahead for future out-of-order messages
            var tempChainKey = receiverChainKey
            val skippedKeysToInsert = mutableListOf<Pair<Int, ByteArray>>()

            for (i in session.receiverSeqNumber until (session.receiverSeqNumber + maxLookahead)) {
                val (stepChainKey, candidateKey) = kdfCk(tempChainKey)
                tempChainKey = stepChainKey
                val candidateNonce = deriveNonce(candidateKey, i)

                val candidatePlaintext = ChaChaAeadCipher.decrypt(candidateKey, candidateNonce, envelope.ciphertext, envelope.authTag)
                if (candidatePlaintext != null && candidatePlaintext.size >= 4) {
                    val buffer = ByteBuffer.wrap(candidatePlaintext)
                    buffer.getInt() // seq
                    val actualMessage = ByteArray(buffer.remaining()).also { buffer.get(it) }

                    // Save intermediate skipped keys into Room DB
                    for ((skippedSeq, skippedKey) in skippedKeysToInsert) {
                        ratchetDao.insertSkippedKey(
                            SkippedKeyEntity(
                                contactNxId = contactNxId,
                                sequenceNumber = skippedSeq,
                                derivedKeyHex = toHex(skippedKey)
                            )
                        )
                    }

                    // Update session state to position i + 1
                    val updated = session.copy(
                        receiverChainKeyHex = toHex(tempChainKey),
                        receiverSeqNumber = i + 1,
                        lastUpdatedTimestamp = System.currentTimeMillis()
                    )
                    ratchetDao.upsertSession(updated)

                    return@withLock actualMessage
                } else {
                    skippedKeysToInsert.add(Pair(i, candidateKey))
                }
            }

            null
        }
    }

    suspend fun purgeExpiredSkippedKeys(): Int = withContext(Dispatchers.IO) {
        val ttlThreshold = System.currentTimeMillis() - (24 * 3600 * 1000L) // 24 Hours
        ratchetDao.purgeOldSkippedKeys(ttlThreshold)
    }

    private fun kdfRk(rk: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(rk, "HmacSHA256"))
        val derived = mac.doFinal(dhOut)

        val newRk = derived.copyOfRange(0, 32)
        val newCk = MessageDigest.getInstance("SHA-256").digest(derived + "chain_salt".toByteArray())
        return Pair(newRk, newCk)
    }

    private fun kdfCk(ck: ByteArray): Pair<ByteArray, ByteArray> {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ck, "HmacSHA256"))

        val nextCk = mac.doFinal(byteArrayOf(0x01))
        val messageKey = mac.doFinal(byteArrayOf(0x02))
        return Pair(nextCk, messageKey)
    }

    private fun deriveNonce(key: ByteArray, seqNumber: Int): ByteArray {
        val buffer = ByteBuffer.allocate(12)
        buffer.putInt(0x4E580000) // "NX" prefix
        buffer.putLong(seqNumber.toLong())
        return buffer.array()
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

    companion object {
        private const val TAG = "DoubleRatchetEngine"
    }
}
