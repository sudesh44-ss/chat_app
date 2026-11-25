package com.nexa.app.core.crypto

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class SenderKeyRecord(
    val epoch: Long,
    var iteration: Int,
    var chainKey: ByteArray,
    val signingPrivateKey: ByteArray? = null,
    val signingPublicKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SenderKeyRecord
        if (epoch != other.epoch) return false
        if (iteration != other.iteration) return false
        if (!chainKey.contentEquals(other.chainKey)) return false
        if (!signingPublicKey.contentEquals(other.signingPublicKey)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = epoch.hashCode()
        result = 31 * result + iteration
        result = 31 * result + chainKey.contentHashCode()
        result = 31 * result + signingPublicKey.contentHashCode()
        return result
    }
}

data class SenderKeyDistributionMessage(
    val groupId: String,
    val senderNxId: String,
    val epoch: Long,
    val iteration: Int,
    val chainKey: ByteArray,
    val signingPublicKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SenderKeyDistributionMessage
        if (groupId != other.groupId) return false
        if (senderNxId != other.senderNxId) return false
        if (epoch != other.epoch) return false
        if (iteration != other.iteration) return false
        if (!chainKey.contentEquals(other.chainKey)) return false
        if (!signingPublicKey.contentEquals(other.signingPublicKey)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + senderNxId.hashCode()
        result = 31 * result + epoch.hashCode()
        result = 31 * result + iteration
        result = 31 * result + chainKey.contentHashCode()
        result = 31 * result + signingPublicKey.contentHashCode()
        return result
    }
}

data class GroupCiphertext(
    val groupId: String,
    val senderNxId: String,
    val epoch: Long,
    val iteration: Int,
    val ciphertext: ByteArray,
    val authTag: ByteArray,
    val nonce: ByteArray,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GroupCiphertext
        if (groupId != other.groupId) return false
        if (senderNxId != other.senderNxId) return false
        if (epoch != other.epoch) return false
        if (iteration != other.iteration) return false
        if (!ciphertext.contentEquals(other.ciphertext)) return false
        if (!authTag.contentEquals(other.authTag)) return false
        if (!nonce.contentEquals(other.nonce)) return false
        if (!signature.contentEquals(other.signature)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + senderNxId.hashCode()
        result = 31 * result + epoch.hashCode()
        result = 31 * result + iteration
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + authTag.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

/**
 * High-performance Group Sender Key cryptographic engine.
 * Implements Signal-style sender keys using ChaCha20-Poly1305 and Ed25519 signatures.
 * Enables single-encryption fan-out to up to 400 members with forward secrecy and epoch rotation.
 */
class GroupSenderKeyEngine {

    private val secureRandom = SecureRandom()

    // Key format: "groupId:senderNxId" -> SenderKeyRecord
    private val senderKeyStore = ConcurrentHashMap<String, SenderKeyRecord>()

    // Skipped message keys: "groupId:senderNxId:epoch:iteration" -> 32-byte MessageKey
    private val skippedMessageKeys = ConcurrentHashMap<String, ByteArray>()

    companion object {
        private const val MAX_SKIP = 2000
        private val MESSAGE_KEY_SEED = byteArrayOf(0x01)
        private val CHAIN_KEY_SEED = byteArrayOf(0x02)
    }

    /**
     * Initializes or rotates our sender key for a group.
     * When a member is removed or epoch advances, call this to guarantee post-compromise security.
     */
    fun createOrRotateSenderKey(groupId: String, myNxId: String): Pair<SenderKeyRecord, SenderKeyDistributionMessage> {
        val existing = senderKeyStore["$groupId:$myNxId"]
        val nextEpoch = (existing?.epoch ?: 0L) + 1L

        val chainKey = ByteArray(32).also { secureRandom.nextBytes(it) }
        val signingPriv = ByteArray(32).also { secureRandom.nextBytes(it) }
        val signingPub = Ed25519Signer.derivePublicKey(signingPriv)

        val record = SenderKeyRecord(
            epoch = nextEpoch,
            iteration = 0,
            chainKey = chainKey,
            signingPrivateKey = signingPriv,
            signingPublicKey = signingPub
        )

        senderKeyStore["$groupId:$myNxId"] = record

        val distribution = SenderKeyDistributionMessage(
            groupId = groupId,
            senderNxId = myNxId,
            epoch = nextEpoch,
            iteration = 0,
            chainKey = chainKey.copyOf(),
            signingPublicKey = signingPub.copyOf()
        )

        return Pair(record, distribution)
    }

    /**
     * Ingests a SenderKeyDistributionMessage received from a peer over pairwise Double Ratchet.
     */
    fun processSenderKeyDistribution(distribution: SenderKeyDistributionMessage) {
        val key = "${distribution.groupId}:${distribution.senderNxId}"
        val existing = senderKeyStore[key]

        // Reject if older or equal epoch
        if (existing != null && distribution.epoch <= existing.epoch) {
            return
        }

        val record = SenderKeyRecord(
            epoch = distribution.epoch,
            iteration = distribution.iteration,
            chainKey = distribution.chainKey.copyOf(),
            signingPrivateKey = null,
            signingPublicKey = distribution.signingPublicKey.copyOf()
        )
        senderKeyStore[key] = record
    }

    /**
     * Encrypts a group message using our sender key.
     * Ratchets the chain key forward monotonically.
     */
    @Synchronized
    fun encrypt(groupId: String, myNxId: String, plaintext: ByteArray): GroupCiphertext {
        var record = senderKeyStore["$groupId:$myNxId"]
        if (record == null || record.signingPrivateKey == null) {
            val (newRecord, _) = createOrRotateSenderKey(groupId, myNxId)
            record = newRecord
        }

        val messageKey = deriveMessageKey(record.chainKey)
        record.chainKey = ratchetChainKey(record.chainKey)
        val currentIteration = record.iteration
        record.iteration += 1

        val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
        val aad = "${groupId}:${myNxId}:${record.epoch}:${currentIteration}".toByteArray(Charsets.UTF_8)
        val (ciphertext, authTag) = ChaChaAeadCipher.encrypt(messageKey, nonce, plaintext, aad)

        // Sign ciphertext + authTag + nonce + aad with sender's Ed25519 signing key
        val toSign = ciphertext + authTag + nonce + aad
        val signature = Ed25519Signer.sign(toSign, record.signingPrivateKey!!)

        return GroupCiphertext(
            groupId = groupId,
            senderNxId = myNxId,
            epoch = record.epoch,
            iteration = currentIteration,
            ciphertext = ciphertext,
            authTag = authTag,
            nonce = nonce,
            signature = signature
        )
    }

    /**
     * Decrypts a group message from a peer.
     * Handles fast-forwarding ratchet and verifies Ed25519 signature.
     */
    @Synchronized
    fun decrypt(msg: GroupCiphertext): ByteArray? {
        val recordKey = "${msg.groupId}:${msg.senderNxId}"
        val record = senderKeyStore[recordKey] ?: return null

        // Mismatched epoch: cannot decrypt with current epoch key
        if (record.epoch != msg.epoch) {
            return null
        }

        // Verify signature before attempting ratchet
        val aad = "${msg.groupId}:${msg.senderNxId}:${msg.epoch}:${msg.iteration}".toByteArray(Charsets.UTF_8)
        val toVerify = msg.ciphertext + msg.authTag + msg.nonce + aad
        if (!Ed25519Signer.verify(toVerify, msg.signature, record.signingPublicKey)) {
            return null
        }

        // Check if message key was previously skipped and cached
        val skippedKey = "${msg.groupId}:${msg.senderNxId}:${msg.epoch}:${msg.iteration}"
        val cachedMessageKey = skippedMessageKeys.remove(skippedKey)
        val messageKey = if (cachedMessageKey != null) {
            cachedMessageKey
        } else {
            if (msg.iteration < record.iteration) {
                // Replay or expired message
                return null
            }
            if (msg.iteration - record.iteration > MAX_SKIP) {
                // Exceeded maximum allowable ratchet skip distance
                return null
            }

            // Ratchet forward to target iteration, storing intermediate keys
            while (record.iteration < msg.iteration) {
                val intermediateKey = deriveMessageKey(record.chainKey)
                skippedMessageKeys["${msg.groupId}:${msg.senderNxId}:${msg.epoch}:${record.iteration}"] = intermediateKey
                record.chainKey = ratchetChainKey(record.chainKey)
                record.iteration += 1
            }

            val targetKey = deriveMessageKey(record.chainKey)
            record.chainKey = ratchetChainKey(record.chainKey)
            record.iteration += 1
            targetKey
        }

        return ChaChaAeadCipher.decrypt(messageKey, msg.nonce, msg.ciphertext, msg.authTag, aad)
    }

    private fun deriveMessageKey(chainKey: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(chainKey, "HmacSHA256"))
        return mac.doFinal(MESSAGE_KEY_SEED)
    }

    private fun ratchetChainKey(chainKey: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(chainKey, "HmacSHA256"))
        return mac.doFinal(CHAIN_KEY_SEED)
    }

    fun hasSenderKey(groupId: String, senderNxId: String): Boolean {
        return senderKeyStore.containsKey("$groupId:$senderNxId")
    }

    fun clear() {
        senderKeyStore.clear()
        skippedMessageKeys.clear()
    }
}
