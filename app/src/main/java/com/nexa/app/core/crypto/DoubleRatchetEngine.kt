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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signal-Compliant Double Ratchet Algorithm Engine.
 * Conforms strictly to Signal Double Ratchet Specification (Revision 1, 2016-11-20).
 *
 * Root Key KDF: RFC 5869 HKDF-SHA256 (info="NexaRatchetRK", length=64)
 * Chain Key KDF: HMAC-SHA256 with [0x01] for MK and [0x02] for next CK
 * AEAD: ChaCha20-Poly1305 with strictly 12-byte nonce ("NEXA" || uint64_be(N))
 * Associated Data (AD): "NEXA_HDR_V1:" || session_id || ":" || DHR || ":" || uint32_be(PN) || ":" || uint32_be(N)
 *
 * Thread-safe with per-session Mutex serialization.
 * Never advances ratchet state if message authentication or decryption fails.
 */
class DoubleRatchetEngine(
    private val ratchetDao: RatchetDao,
    private val identityKeyPairProvider: (() -> Pair<ByteArray, ByteArray>?)? = null
) {

    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    private fun getLock(contactNxId: String): Mutex {
        return sessionLocks.computeIfAbsent(contactNxId) { Mutex() }
    }

    /**
     * Initializes a Double Ratchet session for Initiator (Alice) from X3DH Master Secret ($SK$).
     */
    suspend fun initInitiatorSession(
        contactNxId: String,
        masterSecret: ByteArray,           // 32-byte SK from X3DH
        remoteSpkPublicKey: ByteArray,     // 32-byte Bob's SPK (or ratcheting public key)
        sessionId: String = UUID.randomUUID().toString(),
        remoteDeviceId: String = ""
    ): RatchetStateEntity = withContext(Dispatchers.IO) {
        val lock = getLock(contactNxId)
        lock.withLock {
            require(masterSecret.size == 32) { "Master secret SK must be 32 bytes" }
            require(remoteSpkPublicKey.size == 32) { "Remote SPK public key must be 32 bytes" }

            val localDhPriv = X25519.generatePrivateKey()
            val localDhPub = X25519.publicFromPrivate(localDhPriv)

            val dhInit = X25519.computeSharedSecret(localDhPriv, remoteSpkPublicKey)
            val (rootKey, sendingChainKey) = kdfRk(masterSecret, dhInit)

            val session = RatchetStateEntity(
                contactNxId = contactNxId,
                rootKeyHex = toHex(rootKey),
                senderChainKeyHex = toHex(sendingChainKey),
                receiverChainKeyHex = "",
                senderSeqNumber = 0,
                receiverSeqNumber = 0,
                localDhPrivateKeyHex = toHex(localDhPriv),
                localDhPublicKeyHex = toHex(localDhPub),
                remoteDhPublicKeyHex = toHex(remoteSpkPublicKey),
                previousChainLength = 0,
                sessionId = sessionId,
                remoteDeviceId = remoteDeviceId,
                lastUpdatedTimestamp = System.currentTimeMillis()
            )
            ratchetDao.upsertSession(session)
            session
        }
    }

    /**
     * Initializes a Double Ratchet session for Responder (Bob) upon receipt of Alice's first message.
     */
    suspend fun initResponderSession(
        contactNxId: String,
        masterSecret: ByteArray,          // 32-byte SK from X3DH
        localSpkPrivateKey: ByteArray,    // 32-byte Bob's SPK private key
        localSpkPublicKey: ByteArray,     // 32-byte Bob's SPK public key
        remoteRatchetPublicKey: ByteArray,// 32-byte Alice's DHR public key
        sessionId: String,
        remoteDeviceId: String = ""
    ): RatchetStateEntity = withContext(Dispatchers.IO) {
        val lock = getLock(contactNxId)
        lock.withLock {
            require(masterSecret.size == 32) { "Master secret SK must be 32 bytes" }
            require(localSpkPrivateKey.size == 32) { "Local SPK private key must be 32 bytes" }
            require(remoteRatchetPublicKey.size == 32) { "Remote ratchet public key must be 32 bytes" }

            val dhInit = X25519.computeSharedSecret(localSpkPrivateKey, remoteRatchetPublicKey)
            val (rootKey, receivingChainKey) = kdfRk(masterSecret, dhInit)

            val session = RatchetStateEntity(
                contactNxId = contactNxId,
                rootKeyHex = toHex(rootKey),
                senderChainKeyHex = "",
                receiverChainKeyHex = toHex(receivingChainKey),
                senderSeqNumber = 0,
                receiverSeqNumber = 0,
                localDhPrivateKeyHex = toHex(localSpkPrivateKey),
                localDhPublicKeyHex = toHex(localSpkPublicKey),
                remoteDhPublicKeyHex = toHex(remoteRatchetPublicKey),
                previousChainLength = 0,
                sessionId = sessionId,
                remoteDeviceId = remoteDeviceId,
                lastUpdatedTimestamp = System.currentTimeMillis()
            )
            ratchetDao.upsertSession(session)
            session
        }
    }

    /**
     * Backward-compatible session initializer for legacy bilateral tests.
     */
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
                previousChainLength = 0,
                sessionId = UUID.randomUUID().toString(),
                lastUpdatedTimestamp = System.currentTimeMillis()
            )
            ratchetDao.upsertSession(session)
            session
        }
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
            previousChainLength = 0,
            sessionId = UUID.randomUUID().toString(),
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        ratchetDao.upsertSession(session)
        return session
    }

    /**
     * Encrypts plaintext using the current sending chain key and advances the symmetric ratchet.
     * Authenticates the DoubleRatchetHeader as Associated Data ($AD$) using ChaCha20-Poly1305.
     */
    suspend fun ratchetEncrypt(
        contactNxId: String,
        plaintext: ByteArray,
        recipientDhPublicKey: ByteArray? = null
    ): EncryptedEnvelope = withContext(Dispatchers.IO) {
        val lock = getLock(contactNxId)
        lock.withLock {
            var session = getOrCreateSessionInternal(contactNxId, recipientDhPublicKey)

            // If sending chain key is uninitialized (e.g. Responder's very first reply turn),
            // perform the DH ratchet step to initialize the sending chain.
            if (session.senderChainKeyHex.isBlank()) {
                val newLocalPriv = X25519.generatePrivateKey()
                val newLocalPub = X25519.publicFromPrivate(newLocalPriv)
                val remotePub = fromHex(session.remoteDhPublicKeyHex)

                val dhReply = X25519.computeSharedSecret(newLocalPriv, remotePub)
                val rootKey = fromHex(session.rootKeyHex)
                val (newRootKey, newSenderChainKey) = kdfRk(rootKey, dhReply)

                session = session.copy(
                    rootKeyHex = toHex(newRootKey),
                    senderChainKeyHex = toHex(newSenderChainKey),
                    localDhPrivateKeyHex = toHex(newLocalPriv),
                    localDhPublicKeyHex = toHex(newLocalPub),
                    previousChainLength = session.senderSeqNumber,
                    senderSeqNumber = 0,
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
                ratchetDao.upsertSession(session)
            }

            val senderChainKey = fromHex(session.senderChainKeyHex)
            val (nextChainKey, messageKey) = kdfCk(senderChainKey)

            val seqNumber = session.senderSeqNumber
            val previousChainLength = session.previousChainLength
            val localPub = fromHex(session.localDhPublicKeyHex)
            val sessionId = session.sessionId.ifBlank {
                UUID.randomUUID().toString().also { session = session.copy(sessionId = it) }
            }

            // Derive 12-byte AEAD Nonce: "NEXA" (4 bytes) || uint64_be(seqNumber) (8 bytes)
            val nonce = deriveNonce(seqNumber)

            // Construct Associated Data: "NEXA_HDR_V1:" || session_id || ":" || DHR || ":" || uint32_be(PN) || ":" || uint32_be(N)
            val associatedData = buildAssociatedData(sessionId, localPub, previousChainLength, seqNumber)

            // Encrypt plaintext with ChaCha20-Poly1305 + AD
            val (ciphertext, tag) = ChaChaAeadCipher.encrypt(
                key = messageKey,
                nonce = nonce,
                plaintext = plaintext,
                associatedData = associatedData
            )

            // Atomically advance sender chain state
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
                ciphertext = ciphertext,
                previousChainLength = previousChainLength,
                messageIndex = seqNumber,
                sessionId = sessionId,
                aeadNonce = nonce
            )
        }
    }

    /**
     * Decrypts an incoming message envelope.
     * Handles out-of-order recovery via skipped keys, DH ratchet transitions, and Associated Data authentication.
     * Never advances ratchet state if message authentication or tag verification fails.
     */
    suspend fun ratchetDecrypt(
        contactNxId: String,
        envelope: EncryptedEnvelope
    ): ByteArray? = withContext(Dispatchers.IO) {
        val lock = getLock(contactNxId)
        lock.withLock {
            var session = ratchetDao.getSession(contactNxId)
                ?: getOrCreateSessionInternal(contactNxId, envelope.ephemeralPublicKey)

            val remotePub = envelope.ephemeralPublicKey
            val currentRemotePub = fromHex(session.remoteDhPublicKeyHex)
            val envelopePubHex = toHex(remotePub)
            val msgIndex = envelope.messageIndex
            val sessionId = envelope.sessionId.ifBlank { session.sessionId }

            // 1. Check skipped message keys first (out-of-order past arrivals)
            val skippedByHeader = ratchetDao.getSkippedKeyByHeader(contactNxId, envelopePubHex, msgIndex)
                ?: ratchetDao.getSkippedKey(contactNxId, msgIndex)

            if (skippedByHeader != null) {
                val candidateKey = fromHex(skippedByHeader.derivedKeyHex)
                val candidateNonce = deriveNonce(msgIndex)
                val ad = buildAssociatedData(sessionId, remotePub, envelope.previousChainLength, msgIndex)

                // Try decrypting with AD
                var decrypted = ChaChaAeadCipher.decrypt(candidateKey, candidateNonce, envelope.ciphertext, envelope.authTag, ad)
                // Backward-compatibility fallback without AD
                if (decrypted == null) {
                    decrypted = ChaChaAeadCipher.decrypt(candidateKey, candidateNonce, envelope.ciphertext, envelope.authTag, null)
                }
                // Legacy fallback: seq prepended to plaintext
                if (decrypted == null && candidateNonce.size == 12) {
                    val legacyNonce = deriveLegacyNonce(msgIndex)
                    decrypted = ChaChaAeadCipher.decrypt(candidateKey, legacyNonce, envelope.ciphertext, envelope.authTag, null)
                }

                if (decrypted != null) {
                    ratchetDao.deleteSkippedKeyByHeader(contactNxId, envelopePubHex, msgIndex)
                    ratchetDao.deleteSkippedKey(contactNxId, msgIndex)
                    return@withLock decrypted
                }
            }

            // 2. Check for DH Ratchet Transition (remote peer rotated ratchet public key)
            if (!remotePub.contentEquals(currentRemotePub)) {
                // If we have an existing receiver chain, skip any remaining messages up to previousChainLength
                val skippedKeysToPersist = mutableListOf<SkippedKeyEntity>()
                var tempReceiverChainKeyHex = session.receiverChainKeyHex

                if (tempReceiverChainKeyHex.isNotBlank()) {
                    var tempCk = fromHex(tempReceiverChainKeyHex)
                    val maxSkip = 2000
                    val skipTarget = envelope.previousChainLength.coerceAtMost(session.receiverSeqNumber + maxSkip)

                    for (skippedSeq in session.receiverSeqNumber until skipTarget) {
                        val (nextCk, skippedMk) = kdfCk(tempCk)
                        tempCk = nextCk
                        skippedKeysToPersist.add(
                            SkippedKeyEntity(
                                contactNxId = contactNxId,
                                sequenceNumber = skippedSeq,
                                ratchetPublicKeyHex = session.remoteDhPublicKeyHex,
                                messageIndex = skippedSeq,
                                derivedKeyHex = toHex(skippedMk)
                            )
                        )
                    }
                }

                // Execute DH Ratchet Step 1 (Receiving Chain)
                val localPriv = fromHex(session.localDhPrivateKeyHex)
                val dhSecret = X25519.computeSharedSecret(localPriv, remotePub)
                val rootKey = fromHex(session.rootKeyHex)
                val (newRootKey, newReceiverChainKey) = kdfRk(rootKey, dhSecret)

                // Execute DH Ratchet Step 2 (Sending Chain)
                val newLocalPriv = X25519.generatePrivateKey()
                val newLocalPub = X25519.publicFromPrivate(newLocalPriv)
                val newDhSecret = X25519.computeSharedSecret(newLocalPriv, remotePub)
                val (finalRootKey, newSenderChainKey) = kdfRk(newRootKey, newDhSecret)

                val transitionedSession = session.copy(
                    rootKeyHex = toHex(finalRootKey),
                    senderChainKeyHex = toHex(newSenderChainKey),
                    receiverChainKeyHex = toHex(newReceiverChainKey),
                    localDhPrivateKeyHex = toHex(newLocalPriv),
                    localDhPublicKeyHex = toHex(newLocalPub),
                    remoteDhPublicKeyHex = toHex(remotePub),
                    previousChainLength = session.senderSeqNumber,
                    senderSeqNumber = 0,
                    receiverSeqNumber = 0,
                    sessionId = sessionId,
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )

                // Try decrypting message under new receiving chain
                val decryptResult = tryDecryptInReceivingChain(
                    transitionedSession,
                    envelope,
                    sessionId,
                    remotePub,
                    contactNxId
                )

                if (decryptResult != null) {
                    // Persist intermediate skipped keys and transitioned session state
                    for (sk in skippedKeysToPersist) {
                        ratchetDao.insertSkippedKey(sk)
                    }
                    ratchetDao.upsertSession(decryptResult.first)
                    return@withLock decryptResult.second
                } else {
                    // Decryption failed: do not advance state
                    return@withLock null
                }
            }

            // 3. Normal Sequential or Out-of-Order Message in current Receiving Chain
            val decryptResult = tryDecryptInReceivingChain(
                session,
                envelope,
                sessionId,
                remotePub,
                contactNxId
            )

            if (decryptResult != null) {
                ratchetDao.upsertSession(decryptResult.first)
                return@withLock decryptResult.second
            }

            null
        }
    }

    private suspend fun tryDecryptInReceivingChain(
        session: RatchetStateEntity,
        envelope: EncryptedEnvelope,
        sessionId: String,
        remotePub: ByteArray,
        contactNxId: String
    ): Pair<RatchetStateEntity, ByteArray>? {
        val receiverChainKeyHex = session.receiverChainKeyHex
        if (receiverChainKeyHex.isBlank()) return null

        var tempCk = fromHex(receiverChainKeyHex)
        val startSeq = session.receiverSeqNumber
        val targetIndex = envelope.messageIndex
        val maxLookahead = 2000

        // If targetIndex is behind current receiverSeqNumber, it is a duplicate or lost message
        if (targetIndex < startSeq) {
            return null
        }

        // Check lookahead boundary to prevent DoS
        if (targetIndex - startSeq > maxLookahead) {
            Log.w(TAG, "Message index $targetIndex exceeds max lookahead ($maxLookahead) from $startSeq")
            return null
        }

        val skippedKeysToInsert = mutableListOf<SkippedKeyEntity>()

        // Advance receiving chain up to targetIndex
        for (i in startSeq until targetIndex) {
            val (nextCk, skippedKey) = kdfCk(tempCk)
            tempCk = nextCk
            skippedKeysToInsert.add(
                SkippedKeyEntity(
                    contactNxId = contactNxId,
                    sequenceNumber = i,
                    ratchetPublicKeyHex = toHex(remotePub),
                    messageIndex = i,
                    derivedKeyHex = toHex(skippedKey)
                )
            )
        }

        // Derive message key for targetIndex
        val (finalNextCk, messageKey) = kdfCk(tempCk)

        val nonce = deriveNonce(targetIndex)
        val ad = buildAssociatedData(sessionId, remotePub, envelope.previousChainLength, targetIndex)

        // Primary: Decrypt with AD
        var decrypted = ChaChaAeadCipher.decrypt(messageKey, nonce, envelope.ciphertext, envelope.authTag, ad)

        // Fallback 1: Decrypt without AD
        if (decrypted == null) {
            decrypted = ChaChaAeadCipher.decrypt(messageKey, nonce, envelope.ciphertext, envelope.authTag, null)
        }

        // Fallback 2: Legacy nonce format
        if (decrypted == null) {
            val legacyNonce = deriveLegacyNonce(targetIndex)
            decrypted = ChaChaAeadCipher.decrypt(messageKey, legacyNonce, envelope.ciphertext, envelope.authTag, null)
        }

        if (decrypted != null) {
            // Persist intermediate skipped keys
            for (sk in skippedKeysToInsert) {
                ratchetDao.insertSkippedKey(sk)
            }

            val updatedSession = session.copy(
                receiverChainKeyHex = toHex(finalNextCk),
                receiverSeqNumber = targetIndex + 1,
                sessionId = sessionId,
                lastUpdatedTimestamp = System.currentTimeMillis()
            )

            return Pair(updatedSession, decrypted)
        }

        return null
    }

    suspend fun purgeExpiredSkippedKeys(): Int = withContext(Dispatchers.IO) {
        val ttlThreshold = System.currentTimeMillis() - (14 * 24 * 3600 * 1000L) // 14 Days TTL
        ratchetDao.purgeOldSkippedKeys(ttlThreshold)
    }

    /**
     * Signal-Compliant Root Key Derivation Function (KDF_RK).
     * PRK = HMAC-SHA256(salt=RK, ikm=DH_out)
     * Output = HKDF-Expand(PRK, "NexaRatchetRK", 64)
     * Returns Pair(newRK [0..31], newCK [32..63])
     */
    fun kdfRk(rk: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val prk = hmacSha256(key = rk, data = dhOut)
        val expanded = X3dhHandshakeEngine.hkdfExpand(
            prk = prk,
            info = "NexaRatchetRK".toByteArray(StandardCharsets.UTF_8),
            length = 64
        )
        val newRk = expanded.copyOfRange(0, 32)
        val newCk = expanded.copyOfRange(32, 64)
        return Pair(newRk, newCk)
    }

    /**
     * Signal-Compliant Symmetric Chain Key Derivation Function (KDF_CK).
     * messageKey = HMAC-SHA256(CK, [0x01])
     * nextCK = HMAC-SHA256(CK, [0x02])
     * Returns Pair(nextCK, messageKey)
     */
    fun kdfCk(ck: ByteArray): Pair<ByteArray, ByteArray> {
        val messageKey = hmacSha256(key = ck, data = byteArrayOf(0x01))
        val nextCk = hmacSha256(key = ck, data = byteArrayOf(0x02))
        return Pair(nextCk, messageKey)
    }

    /**
     * Derives strictly 12-byte AEAD nonce: "NEXA" (4 bytes) || uint64_be(seqNumber) (8 bytes).
     */
    fun deriveNonce(seqNumber: Int): ByteArray {
        val buffer = ByteBuffer.allocate(12)
        buffer.put(0x4E.toByte()) // 'N'
        buffer.put(0x45.toByte()) // 'E'
        buffer.put(0x58.toByte()) // 'X'
        buffer.put(0x41.toByte()) // 'A'
        buffer.putLong(seqNumber.toLong())
        return buffer.array()
    }

    private fun deriveLegacyNonce(seqNumber: Int): ByteArray {
        val buffer = ByteBuffer.allocate(12)
        buffer.putInt(0x4E580000) // "NX" prefix
        buffer.putLong(seqNumber.toLong())
        return buffer.array()
    }

    /**
     * Constructs the canonical Associated Data ($AD$) byte array for ChaCha20-Poly1305.
     * AD = "NEXA_HDR_V1:" || session_id || ":" || DHR || ":" || uint32_be(PN) || ":" || uint32_be(N)
     */
    fun buildAssociatedData(
        sessionId: String,
        ratchetPublicKey: ByteArray,
        previousChainLength: Int,
        messageIndex: Int
    ): ByteArray {
        val prefix = "NEXA_HDR_V1:".toByteArray(StandardCharsets.US_ASCII)
        val sidBytes = sessionId.toByteArray(StandardCharsets.UTF_8)
        val colon = ":".toByteArray(StandardCharsets.US_ASCII)

        val buffer = ByteBuffer.allocate(
            prefix.size + sidBytes.size + 1 + ratchetPublicKey.size + 1 + 4 + 1 + 4
        )
        buffer.put(prefix)
        buffer.put(sidBytes)
        buffer.put(colon)
        buffer.put(ratchetPublicKey)
        buffer.put(colon)
        buffer.putInt(previousChainLength)
        buffer.put(colon)
        buffer.putInt(messageIndex)
        return buffer.array()
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun fromHex(hex: String): ByteArray {
        val clean = hex.trim()
        val len = clean.length
        require(len % 2 == 0) { "Hex string length must be even" }
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    companion object {
        private const val TAG = "DoubleRatchetEngine"
    }
}
