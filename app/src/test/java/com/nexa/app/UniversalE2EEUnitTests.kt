package com.nexa.app

import com.google.crypto.tink.subtle.X25519
import com.nexa.app.core.crypto.Base91Codec
import com.nexa.app.core.crypto.ChaChaAeadCipher
import com.nexa.app.core.crypto.CompactWireCodec
import com.nexa.app.core.crypto.DoubleRatchetEngine
import com.nexa.app.core.crypto.EncryptedEnvelope
import com.nexa.app.data.local.dao.RatchetDao
import com.nexa.app.data.local.entity.RatchetStateEntity
import com.nexa.app.data.local.entity.SkippedKeyEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class UniversalE2EEUnitTests {

    @Test
    fun compactWireCodec_overheadMatches49Bytes() {
        assertEquals(49, EncryptedEnvelope.HEADER_OVERHEAD_SIZE)
        assertEquals(49, CompactWireCodec.getFixedOverheadSize())
    }

    @Test
    fun compactWireCodec_serializationAndDeserialization_preservesBinaryIntegrity() {
        val ephemeralPk = ByteArray(32).also { Random(11).nextBytes(it) }
        val authTag = ByteArray(16).also { Random(12).nextBytes(it) }
        val ciphertext = "Universal transport E2EE payload text".toByteArray()

        val envelope = EncryptedEnvelope(
            version = 0x01,
            ephemeralPublicKey = ephemeralPk,
            authTag = authTag,
            ciphertext = ciphertext
        )

        val wireBytes = CompactWireCodec.serialize(envelope)
        assertEquals(49 + ciphertext.size, wireBytes.size)

        val restored = CompactWireCodec.deserialize(wireBytes)
        assertNotNull(restored)
        assertEquals(0x01.toByte(), restored!!.version)
        assertTrue(ephemeralPk.contentEquals(restored.ephemeralPublicKey))
        assertTrue(authTag.contentEquals(restored.authTag))
        assertTrue(ciphertext.contentEquals(restored.ciphertext))
    }

    @Test
    fun compactWireCodec_truncatedBytes_returnsNull() {
        val truncated = ByteArray(48) // 1 byte less than 49
        assertNull(CompactWireCodec.deserialize(truncated))
    }

    @Test
    fun base91Codec_arbitraryBinaryWithNullBytes_roundtrip() {
        // Binary containing null bytes, high bytes, boundary values
        val binaryData = byteArrayOf(0x00, 0x00, 0x01, 0x4E, 0x58, 0xFF.toByte(), 0xFE.toByte(), 0x00, 0x7F, 0x80.toByte())
        val encoded = Base91Codec.encode(binaryData)
        val decoded = Base91Codec.decode(encoded)

        assertTrue(binaryData.contentEquals(decoded))

        // Large random payload
        val randomBytes = ByteArray(1024).also { Random(42).nextBytes(it) }
        val randomEncoded = Base91Codec.encode(randomBytes)
        val randomDecoded = Base91Codec.decode(randomEncoded)
        assertTrue(randomBytes.contentEquals(randomDecoded))
    }

    @Test
    fun chaChaAeadCipher_encryptionAndDecryption_roundtrip() {
        val key = ByteArray(32).also { Random(55).nextBytes(it) }
        val nonce = ByteArray(12).also { Random(56).nextBytes(it) }
        val plaintext = "Hello via military-grade ChaCha20-Poly1305 E2EE".toByteArray()

        val (ciphertext, tag) = ChaChaAeadCipher.encrypt(key, nonce, plaintext)
        assertEquals(16, tag.size)
        assertEquals(plaintext.size, ciphertext.size)

        val decrypted = ChaChaAeadCipher.decrypt(key, nonce, ciphertext, tag)
        assertNotNull(decrypted)
        assertTrue(plaintext.contentEquals(decrypted))

        // Tampering tag returns null safely
        val badTag = tag.copyOf()
        badTag[0] = (badTag[0].toInt() xor 0xFF).toByte()
        assertNull(ChaChaAeadCipher.decrypt(key, nonce, ciphertext, badTag))
    }

    @Test
    fun doubleRatchetEngine_outOfOrderDeliveryRecovery_usingSkippedKeys() = runBlocking {
        val aliceDao = FakeRatchetDao()
        val bobDao = FakeRatchetDao()

        val aliceEngine = DoubleRatchetEngine(aliceDao)
        val bobEngine = DoubleRatchetEngine(bobDao)

        val alicePriv = X25519.generatePrivateKey()
        val alicePub = X25519.publicFromPrivate(alicePriv)

        val bobPriv = X25519.generatePrivateKey()
        val bobPub = X25519.publicFromPrivate(bobPriv)

        // Initialize bilateral sessions
        aliceEngine.initBilateralSession("Bob", alicePriv, alicePub, bobPub)
        bobEngine.initBilateralSession("Alice", bobPriv, bobPub, alicePub)

        // Alice encrypts Message 0, Message 1, Message 2
        val msg0 = "First message".toByteArray()
        val msg1 = "Second message".toByteArray()
        val msg2 = "Third message".toByteArray()

        val env0 = aliceEngine.ratchetEncrypt("Bob", msg0)
        val env1 = aliceEngine.ratchetEncrypt("Bob", msg1)
        val env2 = aliceEngine.ratchetEncrypt("Bob", msg2)

        // Bob receives Message 0
        val dec0 = bobEngine.ratchetDecrypt("Alice", env0)
        assertNotNull(dec0)
        assertTrue(msg0.contentEquals(dec0))

        // Bob receives Message 2 out-of-order BEFORE Message 1
        val dec2 = bobEngine.ratchetDecrypt("Alice", env2)
        assertNotNull(dec2)
        assertTrue(msg2.contentEquals(dec2))

        // Message 1 key should be persisted in Bob's skipped keys table
        val skippedKey = bobDao.getSkippedKey("Alice", 1)
        assertNotNull(skippedKey)

        // Now Message 1 arrives late: Bob decrypts it using the stored skipped key!
        val dec1 = bobEngine.ratchetDecrypt("Alice", env1)
        assertNotNull(dec1)
        assertTrue(msg1.contentEquals(dec1))

        // And the skipped key is purged once consumed
        assertNull(bobDao.getSkippedKey("Alice", 1))
    }

    private class FakeRatchetDao : RatchetDao {
        private val sessions = mutableMapOf<String, RatchetStateEntity>()
        private val skippedKeys = mutableMapOf<String, SkippedKeyEntity>()

        override suspend fun upsertSession(session: RatchetStateEntity): Long {
            sessions[session.contactNxId] = session
            return 1L
        }

        override suspend fun getSession(contactNxId: String): RatchetStateEntity? {
            return sessions[contactNxId]
        }

        override suspend fun getSessionById(sessionId: String): RatchetStateEntity? {
            return sessions.values.find { it.sessionId == sessionId }
        }

        override suspend fun deleteSession(contactNxId: String): Int {
            return if (sessions.remove(contactNxId) != null) 1 else 0
        }

        override suspend fun insertSkippedKey(key: SkippedKeyEntity): Long {
            skippedKeys["${key.contactNxId}_${key.sequenceNumber}"] = key
            if (key.ratchetPublicKeyHex.isNotBlank()) {
                skippedKeys["${key.contactNxId}_${key.ratchetPublicKeyHex}_${key.messageIndex}"] = key
            }
            return 1L
        }

        override suspend fun getSkippedKey(contactNxId: String, seq: Int): SkippedKeyEntity? {
            return skippedKeys["${contactNxId}_${seq}"]
        }

        override suspend fun getSkippedKeyByHeader(contactNxId: String, ratchetPubHex: String, msgIdx: Int): SkippedKeyEntity? {
            return skippedKeys["${contactNxId}_${ratchetPubHex}_${msgIdx}"] ?: skippedKeys["${contactNxId}_${msgIdx}"]
        }

        override suspend fun deleteSkippedKey(contactNxId: String, seq: Int): Int {
            return if (skippedKeys.remove("${contactNxId}_${seq}") != null) 1 else 0
        }

        override suspend fun deleteSkippedKeyByHeader(contactNxId: String, ratchetPubHex: String, msgIdx: Int): Int {
            val r1 = skippedKeys.remove("${contactNxId}_${ratchetPubHex}_${msgIdx}") != null
            val r2 = skippedKeys.remove("${contactNxId}_${msgIdx}") != null
            return if (r1 || r2) 1 else 0
        }

        override suspend fun purgeOldSkippedKeys(thresholdTimestamp: Long): Int {
            val count = skippedKeys.count { it.value.createdAtTimestamp < thresholdTimestamp }
            skippedKeys.entries.removeIf { it.value.createdAtTimestamp < thresholdTimestamp }
            return count
        }

        override suspend fun countSkippedKeys(contactNxId: String): Int {
            return skippedKeys.values.count { it.contactNxId == contactNxId }
        }
    }
}
