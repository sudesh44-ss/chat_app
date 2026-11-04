package com.nexa.app

import com.google.crypto.tink.subtle.X25519
import com.nexa.app.core.crypto.ChaChaAeadCipher
import com.nexa.app.core.crypto.DoubleRatchetEngine
import com.nexa.app.core.crypto.Ed25519Signer
import com.nexa.app.core.crypto.EncryptedEnvelope
import com.nexa.app.core.proto.AuthChallengePayload
import com.nexa.app.core.proto.AuthResponsePayload
import com.nexa.app.core.proto.EncryptedMessagePayload
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.core.proto.PacketType
import com.nexa.app.data.local.dao.RatchetDao
import com.nexa.app.data.local.entity.RatchetStateEntity
import com.nexa.app.data.local.entity.SkippedKeyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 1 Comprehensive Security & Messaging Verification Suite:
 * - Two-Device E2EE Verification (Zero local-master-key reliance)
 * - Double Ratchet forward secrecy & out-of-order tolerance
 * - Tamper & bit-flip AEAD rejection
 * - Per-session thread safety & concurrency verification
 * - Protobuf wire format for auth challenge & response
 * - Cryptographic Ed25519 challenge-response handshake with replay protection
 */
class Phase1SecurityAndMessagingTests {

    private val secureRandom = SecureRandom()

    @Test
    fun twoDeviceE2EE_separateDevicesEncryptAndDecryptSuccessfully() = runBlocking {
        // Device A (Alice) and Device B (Bob) have separate hardware and separate DBs
        val aliceDao = InMemoryRatchetDao()
        val bobDao = InMemoryRatchetDao()

        val alicePriv = X25519.generatePrivateKey()
        val alicePub = X25519.publicFromPrivate(alicePriv)

        val bobPriv = X25519.generatePrivateKey()
        val bobPub = X25519.publicFromPrivate(bobPriv)

        val aliceEngine = DoubleRatchetEngine(aliceDao)
        val bobEngine = DoubleRatchetEngine(bobDao)

        // Initialize bilateral sessions (e.g. from mutual friend request / QR scan)
        aliceEngine.initBilateralSession(
            contactNxId = "NX-BOB-987",
            localPriv = alicePriv,
            localPub = alicePub,
            remotePub = bobPub,
            isInitiator = true
        )

        bobEngine.initBilateralSession(
            contactNxId = "NX-ALICE-123",
            localPriv = bobPriv,
            localPub = bobPub,
            remotePub = alicePub,
            isInitiator = false
        )

        // Step 1: Alice sends encrypted message to Bob over wire
        val alicePlaintext = "Hello Bob! This is an E2EE secret from Device A."
        val aliceEnvelope = aliceEngine.ratchetEncrypt("NX-BOB-987", alicePlaintext.toByteArray(StandardCharsets.UTF_8))

        // Device B (Bob) decrypts using ONLY Bob's local ratchet session (zero knowledge of Alice's device key)
        val bobDecryptedBytes = bobEngine.ratchetDecrypt("NX-ALICE-123", aliceEnvelope)
        assertNotNull("Bob must successfully decrypt Alice's message", bobDecryptedBytes)
        assertEquals(alicePlaintext, String(bobDecryptedBytes!!, StandardCharsets.UTF_8))

        // Step 2: Bob replies to Alice
        val bobReply = "Acknowledged Alice! Device B response over Double Ratchet."
        val bobEnvelope = bobEngine.ratchetEncrypt("NX-ALICE-123", bobReply.toByteArray(StandardCharsets.UTF_8))

        // Alice decrypts Bob's reply
        val aliceDecryptedBytes = aliceEngine.ratchetDecrypt("NX-BOB-987", bobEnvelope)
        assertNotNull("Alice must successfully decrypt Bob's reply", aliceDecryptedBytes)
        assertEquals(bobReply, String(aliceDecryptedBytes!!, StandardCharsets.UTF_8))
    }

    @Test
    fun doubleRatchet_multiMessageConversation_advancesChains() = runBlocking {
        val aliceDao = InMemoryRatchetDao()
        val bobDao = InMemoryRatchetDao()

        val alicePriv = X25519.generatePrivateKey()
        val alicePub = X25519.publicFromPrivate(alicePriv)
        val bobPriv = X25519.generatePrivateKey()
        val bobPub = X25519.publicFromPrivate(bobPriv)

        val aliceEngine = DoubleRatchetEngine(aliceDao)
        val bobEngine = DoubleRatchetEngine(bobDao)

        aliceEngine.initBilateralSession("Bob", alicePriv, alicePub, bobPub, isInitiator = true)
        bobEngine.initBilateralSession("Alice", bobPriv, bobPub, alicePub, isInitiator = false)

        val messages = listOf(
            "Message 1 from Alice",
            "Message 2 from Alice",
            "Message 3 from Alice",
            "Message 4 from Alice",
            "Message 5 from Alice"
        )

        for (msg in messages) {
            val env = aliceEngine.ratchetEncrypt("Bob", msg.toByteArray(StandardCharsets.UTF_8))
            val decrypted = bobEngine.ratchetDecrypt("Alice", env)
            assertNotNull(decrypted)
            assertEquals(msg, String(decrypted!!, StandardCharsets.UTF_8))
        }

        // Verify sequence progression
        val aliceSession = aliceDao.getSession("Bob")
        val bobSession = bobDao.getSession("Alice")
        assertEquals(5, aliceSession?.senderSeqNumber)
        assertEquals(5, bobSession?.receiverSeqNumber)
    }

    @Test
    fun doubleRatchet_outOfOrderArrival_handlesSkippedKeysCorrectly() = runBlocking {
        val aliceDao = InMemoryRatchetDao()
        val bobDao = InMemoryRatchetDao()

        val alicePriv = X25519.generatePrivateKey()
        val alicePub = X25519.publicFromPrivate(alicePriv)
        val bobPriv = X25519.generatePrivateKey()
        val bobPub = X25519.publicFromPrivate(bobPriv)

        val aliceEngine = DoubleRatchetEngine(aliceDao)
        val bobEngine = DoubleRatchetEngine(bobDao)

        aliceEngine.initBilateralSession("Bob", alicePriv, alicePub, bobPub, isInitiator = true)
        bobEngine.initBilateralSession("Alice", bobPriv, bobPub, alicePub, isInitiator = false)

        val msg0 = "Msg 0".toByteArray()
        val msg1 = "Msg 1".toByteArray()
        val msg2 = "Msg 2".toByteArray()
        val msg3 = "Msg 3".toByteArray()

        val env0 = aliceEngine.ratchetEncrypt("Bob", msg0)
        val env1 = aliceEngine.ratchetEncrypt("Bob", msg1)
        val env2 = aliceEngine.ratchetEncrypt("Bob", msg2)
        val env3 = aliceEngine.ratchetEncrypt("Bob", msg3)

        // Inverted delivery order: 3 arrives first, then 1, then 0, then 2
        val dec3 = bobEngine.ratchetDecrypt("Alice", env3)
        assertNotNull(dec3)
        assertTrue(msg3.contentEquals(dec3))

        // Intermediate keys (0, 1, 2) must be in skipped keys table
        assertNotNull(bobDao.getSkippedKey("Alice", 0))
        assertNotNull(bobDao.getSkippedKey("Alice", 1))
        assertNotNull(bobDao.getSkippedKey("Alice", 2))

        // Late delivery of 1
        val dec1 = bobEngine.ratchetDecrypt("Alice", env1)
        assertNotNull(dec1)
        assertTrue(msg1.contentEquals(dec1))
        assertNull("Consumed skipped key must be deleted", bobDao.getSkippedKey("Alice", 1))

        // Late delivery of 0
        val dec0 = bobEngine.ratchetDecrypt("Alice", env0)
        assertNotNull(dec0)
        assertTrue(msg0.contentEquals(dec0))
        assertNull("Consumed skipped key must be deleted", bobDao.getSkippedKey("Alice", 0))

        // Late delivery of 2
        val dec2 = bobEngine.ratchetDecrypt("Alice", env2)
        assertNotNull(dec2)
        assertTrue(msg2.contentEquals(dec2))
        assertNull("Consumed skipped key must be deleted", bobDao.getSkippedKey("Alice", 2))
    }

    @Test
    fun doubleRatchet_tamperedCiphertext_rejectedWithNull() = runBlocking {
        val aliceDao = InMemoryRatchetDao()
        val bobDao = InMemoryRatchetDao()

        val alicePriv = X25519.generatePrivateKey()
        val alicePub = X25519.publicFromPrivate(alicePriv)
        val bobPriv = X25519.generatePrivateKey()
        val bobPub = X25519.publicFromPrivate(bobPriv)

        val aliceEngine = DoubleRatchetEngine(aliceDao)
        val bobEngine = DoubleRatchetEngine(bobDao)

        aliceEngine.initBilateralSession("Bob", alicePriv, alicePub, bobPub, isInitiator = true)
        bobEngine.initBilateralSession("Alice", bobPriv, bobPub, alicePub, isInitiator = false)

        val text = "Authentic confidential content"
        val env = aliceEngine.ratchetEncrypt("Bob", text.toByteArray(StandardCharsets.UTF_8))

        // Tamper with ciphertext by flipping one bit
        val tamperedCiphertext = env.ciphertext.copyOf()
        tamperedCiphertext[0] = (tamperedCiphertext[0].toInt() xor 0x01).toByte()
        val tamperedEnv = env.copy(ciphertext = tamperedCiphertext)

        val result = bobEngine.ratchetDecrypt("Alice", tamperedEnv)
        assertNull("Tampered envelope must fail AEAD verification and return null", result)

        // Tamper with authTag
        val tamperedTag = env.authTag.copyOf()
        tamperedTag[0] = (tamperedTag[0].toInt() xor 0xFF).toByte()
        val tamperedTagEnv = env.copy(authTag = tamperedTag)

        val tagResult = bobEngine.ratchetDecrypt("Alice", tamperedTagEnv)
        assertNull("Tampered Poly1305 MAC tag must fail AEAD verification and return null", tagResult)
    }

    @Test
    fun doubleRatchet_concurrentEncryption_threadSafeWithoutCorruption() = runBlocking {
        val dao = InMemoryRatchetDao()
        val priv = X25519.generatePrivateKey()
        val pub = X25519.publicFromPrivate(priv)
        val remote = X25519.publicFromPrivate(X25519.generatePrivateKey())

        val engine = DoubleRatchetEngine(dao)
        engine.initBilateralSession("ContactX", priv, pub, remote, isInitiator = true)

        // Concurrently encrypt 30 messages across Dispatchers.IO
        val tasks = (1..30).map { i ->
            async(Dispatchers.IO) {
                engine.ratchetEncrypt("ContactX", "Concurrent message $i".toByteArray())
            }
        }

        val envelopes = tasks.awaitAll()
        assertEquals(30, envelopes.size)

        // Sequence number must equal exactly 30
        val finalSession = dao.getSession("ContactX")
        assertEquals(30, finalSession?.senderSeqNumber)
    }

    @Test
    fun wirePacket_authChallengeAndResponse_encodeDecodePreserved() {
        val nonce = ByteArray(32).also { secureRandom.nextBytes(it) }
        val now = System.currentTimeMillis()
        val expiry = now + 60_000L

        val challengePacket = NexaWirePacket(
            packetId = "chal_101",
            type = PacketType.PACKET_AUTH_CHALLENGE,
            senderNxId = "SERVER",
            recipientNxId = "NX-USER-1",
            timestamp = now,
            authChallenge = AuthChallengePayload(
                challengeNonce = nonce,
                timestamp = now,
                expiresAt = expiry
            )
        )

        val encoded = challengePacket.encode()
        val decoded = NexaWirePacket.decode(encoded)

        assertEquals(PacketType.PACKET_AUTH_CHALLENGE, decoded.type)
        assertEquals("SERVER", decoded.senderNxId)
        assertEquals("NX-USER-1", decoded.recipientNxId)
        assertNotNull(decoded.authChallenge)
        assertTrue(nonce.contentEquals(decoded.authChallenge!!.challengeNonce))
        assertEquals(now, decoded.authChallenge!!.timestamp)
        assertEquals(expiry, decoded.authChallenge!!.expiresAt)

        // Auth Response packet
        val sig = ByteArray(64).also { secureRandom.nextBytes(it) }
        val pub = ByteArray(32).also { secureRandom.nextBytes(it) }

        val responsePacket = NexaWirePacket(
            packetId = "resp_101",
            type = PacketType.PACKET_AUTH_RESPONSE,
            senderNxId = "NX-USER-1",
            recipientNxId = "SERVER",
            timestamp = now + 100,
            authResponse = AuthResponsePayload(
                signature = sig,
                publicKey = pub,
                timestamp = now + 100
            )
        )

        val respEncoded = responsePacket.encode()
        val respDecoded = NexaWirePacket.decode(respEncoded)

        assertEquals(PacketType.PACKET_AUTH_RESPONSE, respDecoded.type)
        assertEquals("NX-USER-1", respDecoded.senderNxId)
        assertEquals("SERVER", respDecoded.recipientNxId)
        assertNotNull(respDecoded.authResponse)
        assertTrue(sig.contentEquals(respDecoded.authResponse!!.signature))
        assertTrue(pub.contentEquals(respDecoded.authResponse!!.publicKey))
    }

    @Test
    fun ed25519ChallengeResponse_validSignature_verifiesSuccessfully() {
        val seed = ByteArray(32).also { secureRandom.nextBytes(it) }
        val pubKey = Ed25519Signer.derivePublicKey(seed)
        val nonce = ByteArray(32).also { secureRandom.nextBytes(it) }
        val nxId = "NX-DEV-TEST"
        val timestamp = System.currentTimeMillis()

        // Canonical dataToSign: challengeNonce + ":" + nxId + ":" + timestamp
        val dataToSign = nonce + ":$nxId:$timestamp".toByteArray(Charsets.UTF_8)
        val signature = Ed25519Signer.sign(dataToSign, seed)

        assertEquals(64, signature.size)
        assertEquals(32, pubKey.size)

        // Verifier side (Server)
        val verified = Ed25519Signer.verify(dataToSign, signature, pubKey)
        assertTrue("Server verification of valid Ed25519 signature must succeed", verified)

        // Tampered challenge nonce must fail verification
        val tamperedData = nonce.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() } + ":$nxId:$timestamp".toByteArray(Charsets.UTF_8)
        val tamperedVerified = Ed25519Signer.verify(tamperedData, signature, pubKey)
        assertFalse("Tampered challenge data must fail verification", tamperedVerified)

        // Wrong identity must fail verification
        val wrongNxIdData = nonce + ":NX-IMPOSTER:$timestamp".toByteArray(Charsets.UTF_8)
        val wrongVerified = Ed25519Signer.verify(wrongNxIdData, signature, pubKey)
        assertFalse("Imposter NX-ID must fail verification", wrongVerified)

        // Other public key must fail verification
        val otherSeed = ByteArray(32).also { secureRandom.nextBytes(it) }
        val otherPubKey = Ed25519Signer.derivePublicKey(otherSeed)
        val otherVerified = Ed25519Signer.verify(dataToSign, signature, otherPubKey)
        assertFalse("Signature verified with wrong public key must fail", otherVerified)
    }

    @Test
    fun threePartyE2EE_crossUserIsolationAndIncorrectRecipientRejection() = runBlocking {
        // User A (Alice), User B (Bob), User C (Charlie) have independent ratchet states
        val aliceDao = InMemoryRatchetDao()
        val bobDao = InMemoryRatchetDao()
        val charlieDao = InMemoryRatchetDao()

        val alicePriv = X25519.generatePrivateKey()
        val alicePub = X25519.publicFromPrivate(alicePriv)

        val bobPriv = X25519.generatePrivateKey()
        val bobPub = X25519.publicFromPrivate(bobPriv)

        val charliePriv = X25519.generatePrivateKey()
        val charliePub = X25519.publicFromPrivate(charliePriv)

        val aliceEngine = DoubleRatchetEngine(aliceDao)
        val bobEngine = DoubleRatchetEngine(bobDao)
        val charlieEngine = DoubleRatchetEngine(charlieDao)

        // Session A <-> B
        aliceEngine.initBilateralSession("NX-BOB-987", alicePriv, alicePub, bobPub, isInitiator = true)
        bobEngine.initBilateralSession("NX-ALICE-123", bobPriv, bobPub, alicePub, isInitiator = false)

        // Session A <-> C
        aliceEngine.initBilateralSession("NX-CHARLIE-456", alicePriv, alicePub, charliePub, isInitiator = true)
        charlieEngine.initBilateralSession("NX-ALICE-123", charliePriv, charliePub, alicePub, isInitiator = false)

        // 1. A -> B: Alice encrypts to Bob
        val msgAtoB = "Top secret message from Alice to Bob"
        val envAtoB = aliceEngine.ratchetEncrypt("NX-BOB-987", msgAtoB.toByteArray(Charsets.UTF_8))
        assertNotNull(envAtoB)

        // 2. B -> A: Bob decrypts message from Alice
        val bobDecrypted = bobEngine.ratchetDecrypt("NX-ALICE-123", envAtoB)
        assertNotNull(bobDecrypted)
        assertEquals(msgAtoB, String(bobDecrypted!!, Charsets.UTF_8))

        // 3. B -> A: Bob replies to Alice
        val msgBtoA = "Secret reply from Bob to Alice"
        val envBtoA = bobEngine.ratchetEncrypt("NX-ALICE-123", msgBtoA.toByteArray(Charsets.UTF_8))
        val aliceDecryptedFromBob = aliceEngine.ratchetDecrypt("NX-BOB-987", envBtoA)
        assertNotNull(aliceDecryptedFromBob)
        assertEquals(msgBtoA, String(aliceDecryptedFromBob!!, Charsets.UTF_8))

        // 4. A -> C: Alice encrypts to Charlie
        val msgAtoC = "Separate channel message from Alice to Charlie"
        val envAtoC = aliceEngine.ratchetEncrypt("NX-CHARLIE-456", msgAtoC.toByteArray(Charsets.UTF_8))
        val charlieDecrypted = charlieEngine.ratchetDecrypt("NX-ALICE-123", envAtoC)
        assertNotNull(charlieDecrypted)
        assertEquals(msgAtoC, String(charlieDecrypted!!, Charsets.UTF_8))

        // 5. Cross-User Isolation / Eavesdropping Rejection:
        // Charlie attempts to decrypt the message Alice sent to Bob (envAtoB)
        val charlieIllegitimateDecrypt = charlieEngine.ratchetDecrypt("NX-ALICE-123", envAtoB)
        assertNull("Charlie must NOT be able to decrypt message intended for Bob", charlieIllegitimateDecrypt)

        // 6. Incorrect Recipient Rejection:
        // Bob attempts to decrypt message intended for Charlie (envAtoC)
        val bobIllegitimateDecrypt = bobEngine.ratchetDecrypt("NX-ALICE-123", envAtoC)
        assertNull("Bob must NOT be able to decrypt message intended for Charlie", bobIllegitimateDecrypt)

        // 7. Zero Plaintext on Wire Verification
        val rawWireBytes = envAtoB.ciphertext
        assertFalse("Wire ciphertext must never contain plaintext string", String(rawWireBytes).contains("Top secret"))
    }

    private class InMemoryRatchetDao : RatchetDao {
        private val sessions = ConcurrentHashMap<String, RatchetStateEntity>()
        private val skippedKeys = ConcurrentHashMap<String, SkippedKeyEntity>()

        override suspend fun upsertSession(session: RatchetStateEntity): Long {
            sessions[session.contactNxId] = session
            return 1L
        }

        override suspend fun getSession(contactNxId: String): RatchetStateEntity? {
            return sessions[contactNxId]
        }

        override suspend fun insertSkippedKey(key: SkippedKeyEntity): Long {
            skippedKeys["${key.contactNxId}_${key.sequenceNumber}"] = key
            return 1L
        }

        override suspend fun getSkippedKey(contactNxId: String, seq: Int): SkippedKeyEntity? {
            return skippedKeys["${contactNxId}_${seq}"]
        }

        override suspend fun deleteSkippedKey(contactNxId: String, seq: Int): Int {
            return if (skippedKeys.remove("${contactNxId}_${seq}") != null) 1 else 0
        }

        override suspend fun purgeOldSkippedKeys(thresholdTimestamp: Long): Int {
            val count = skippedKeys.count { it.value.createdAtTimestamp < thresholdTimestamp }
            skippedKeys.entries.removeIf { it.value.createdAtTimestamp < thresholdTimestamp }
            return count
        }
    }
}
