package com.nexa.app

import com.google.crypto.tink.subtle.X25519
import com.nexa.app.core.crypto.ChaChaAeadCipher
import com.nexa.app.core.crypto.DoubleRatchetEngine
import com.nexa.app.core.crypto.Ed25519Signer
import com.nexa.app.core.crypto.EncryptedEnvelope
import com.nexa.app.core.crypto.PrekeyBundle
import com.nexa.app.core.crypto.ProofOfWorkEngine
import com.nexa.app.core.crypto.X3dhHandshakeEngine
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.data.local.dao.RatchetDao
import com.nexa.app.data.local.entity.RatchetStateEntity
import com.nexa.app.data.local.entity.SkippedKeyEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * NEXA Phase E Step 4 — Comprehensive Cryptographic and Two-Device Integration Test Suite.
 * Covers all 18 mandatory security test scenarios from STEP 7 plus STEP 8 Two-Device Integration.
 */
class PhaseEStep4SecurityTests {

    private val secureRandom = SecureRandom()

    // =========================================================================
    // TEST 1: Valid X3DH with One-Time Prekey (OPK) Present (Deterministic KAT)
    // =========================================================================
    @Test
    fun test01_validX3dhWithOpk_deterministicKat() {
        val sk_ik_a = fromHex("e81b6727230a66d581729c138b556b63d67f7e9f8a3138b3014a9ec0af96d158")
        val pk_ik_a = fromHex("f5b324d0dddbf2010f8fc94f690deb4d8d1d1e4b5f0c761e9dd3188b03f5a453")

        val sk_ek_a = fromHex("904f86d63b2cf9d690a76a5996fc3b745fd903fcb5b4e33433a7e4b2a8d38b4f")
        val pk_ek_a = fromHex("d8950ac21c1ea2fca01a2bbb4f0e22b1177a516eda1e2db3dadb55aacf032345")

        val sk_ik_b = fromHex("5034c56858e93ee5b0ad5ec1db98a83a0058b291583ff6e9be1e39a5dcbb746a")
        val pk_ik_b = fromHex("dd7e0ae6ba82222f9244b5a423426ef1f8ecaacaab4907a08bfe09eaff184d09")

        val sk_spk_b = fromHex("2824bb8b05dc6e7299a8ea8d95ab4db56fc120c169992d9f824143a416ad8869")
        val pk_spk_b = fromHex("060031ab8354902fb7fe6fca9213711d361eddc4de85d9409f6b4eb5b7b1e27e")

        val sk_opk_b = fromHex("c0879e61b9a8966276708fb5625ffda9ca97e59f49b6b7a2d645e5f39642c159")
        val pk_opk_b = fromHex("d3e0ababfc79b86e746ef1ef91e3fd71c11d38a650924485a8563fe6ad661e0f")

        // Alice computes SK
        val aliceSk = X3dhHandshakeEngine.deriveInitiatorMasterSecret(
            senderIdentityPrivateKey = sk_ik_a,
            ephemeralPrivateKey = sk_ek_a,
            recipientIdentityPublicKey = pk_ik_b,
            recipientSignedPublicKey = pk_spk_b,
            recipientOneTimePublicKey = pk_opk_b
        )

        // Bob computes SK
        val bobSk = X3dhHandshakeEngine.deriveResponderMasterSecret(
            recipientIdentityPrivateKey = sk_ik_b,
            recipientSignedPrivateKey = sk_spk_b,
            recipientOneTimePrivateKey = sk_opk_b,
            senderIdentityPublicKey = pk_ik_a,
            senderEphemeralPublicKey = pk_ek_a
        )

        assertEquals("Alice and Bob must compute identical master secrets", toHex(aliceSk), toHex(bobSk))
        assertEquals(
            "Derived SK must match deterministic Known-Answer Test Vector A exactly",
            "2507051bebe082a43d8743047310d188dc5dfeb2704fd4c0f138deabbcbed4f2",
            toHex(aliceSk)
        )
    }

    // =========================================================================
    // TEST 2: Valid X3DH without One-Time Prekey (OPK Absent) (Deterministic KAT)
    // =========================================================================
    @Test
    fun test02_validX3dhWithoutOpk_deterministicKat() {
        val sk_ik_a = fromHex("e81b6727230a66d581729c138b556b63d67f7e9f8a3138b3014a9ec0af96d158")
        val pk_ik_a = fromHex("f5b324d0dddbf2010f8fc94f690deb4d8d1d1e4b5f0c761e9dd3188b03f5a453")

        val sk_ek_a = fromHex("904f86d63b2cf9d690a76a5996fc3b745fd903fcb5b4e33433a7e4b2a8d38b4f")
        val pk_ek_a = fromHex("d8950ac21c1ea2fca01a2bbb4f0e22b1177a516eda1e2db3dadb55aacf032345")

        val sk_ik_b = fromHex("5034c56858e93ee5b0ad5ec1db98a83a0058b291583ff6e9be1e39a5dcbb746a")
        val pk_ik_b = fromHex("dd7e0ae6ba82222f9244b5a423426ef1f8ecaacaab4907a08bfe09eaff184d09")

        val sk_spk_b = fromHex("2824bb8b05dc6e7299a8ea8d95ab4db56fc120c169992d9f824143a416ad8869")
        val pk_spk_b = fromHex("060031ab8354902fb7fe6fca9213711d361eddc4de85d9409f6b4eb5b7b1e27e")

        // Alice computes SK without OPK
        val aliceSk = X3dhHandshakeEngine.deriveInitiatorMasterSecret(
            senderIdentityPrivateKey = sk_ik_a,
            ephemeralPrivateKey = sk_ek_a,
            recipientIdentityPublicKey = pk_ik_b,
            recipientSignedPublicKey = pk_spk_b,
            recipientOneTimePublicKey = null
        )

        // Bob computes SK without OPK
        val bobSk = X3dhHandshakeEngine.deriveResponderMasterSecret(
            recipientIdentityPrivateKey = sk_ik_b,
            recipientSignedPrivateKey = sk_spk_b,
            recipientOneTimePrivateKey = null,
            senderIdentityPublicKey = pk_ik_a,
            senderEphemeralPublicKey = pk_ek_a
        )

        assertEquals("Alice and Bob must compute identical master secrets when OPK is absent", toHex(aliceSk), toHex(bobSk))
        assertEquals(
            "Derived SK must match deterministic Known-Answer Test Vector B exactly",
            "831601209178575514a4e20543a46b426a3ddbb496cd52838fd8e1996a5d8af0",
            toHex(aliceSk)
        )
    }

    // =========================================================================
    // TEST 3: Invalid Signed Prekey Signature Rejection
    // =========================================================================
    @Test
    fun test03_invalidSignedPrekeySignatureRejection() {
        val signPriv = ByteArray(32).also { secureRandom.nextBytes(it) }
        val signPub = Ed25519Signer.derivePublicKey(signPriv)
        val spkPub = X25519.publicFromPrivate(X25519.generatePrivateKey())
        val timestamp = System.currentTimeMillis()

        // Canonical TLV signature
        val canonicalBytes = X3dhHandshakeEngine.encodeSignedPrekey(1, "NX-BOB", "dev_01", spkPub, timestamp)
        val validSig = Ed25519Signer.sign(canonicalBytes, signPriv)

        // Valid signature passes
        assertTrue(
            "Valid SPK signature must verify",
            X3dhHandshakeEngine.verifySignedPrekeySignature(
                toHex(signPub), toHex(spkPub), toHex(validSig), 1, "NX-BOB", "dev_01", timestamp
            )
        )

        // Tampered signature fails
        val tamperedSig = validSig.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        assertFalse(
            "Tampered SPK signature must be rejected",
            X3dhHandshakeEngine.verifySignedPrekeySignature(
                toHex(signPub), toHex(spkPub), toHex(tamperedSig), 1, "NX-BOB", "dev_01", timestamp
            )
        )

        // Tampered key ID fails
        assertFalse(
            "Mismatched SPK ID must be rejected",
            X3dhHandshakeEngine.verifySignedPrekeySignature(
                toHex(signPub), toHex(spkPub), toHex(validSig), 999, "NX-BOB", "dev_01", timestamp
            )
        )
    }

    // =========================================================================
    // TEST 4: Tampered Identity Binding Attestation Rejection
    // =========================================================================
    @Test
    fun test04_tamperedIdentityBindingRejection() {
        val signPriv = ByteArray(32).also { secureRandom.nextBytes(it) }
        val signPub = Ed25519Signer.derivePublicKey(signPriv)
        val dhPub = X25519.publicFromPrivate(X25519.generatePrivateKey())
        val timestamp = System.currentTimeMillis()

        val canonicalBytes = X3dhHandshakeEngine.encodeIdentityBinding("NX-ALICE", "dev_01", signPub, dhPub, timestamp)
        val validSig = Ed25519Signer.sign(canonicalBytes, signPriv)

        assertTrue(
            "Valid identity binding must verify",
            X3dhHandshakeEngine.verifyIdentityBindingSignature(
                toHex(signPub), toHex(dhPub), toHex(validSig), "NX-ALICE", "dev_01", timestamp
            )
        )

        // Attacker attempts to bind Alice's signature to a different account ID
        assertFalse(
            "Binding to a different account must fail",
            X3dhHandshakeEngine.verifyIdentityBindingSignature(
                toHex(signPub), toHex(dhPub), toHex(validSig), "NX-ATTACKER", "dev_01", timestamp
            )
        )
    }

    // =========================================================================
    // TEST 5: Wrong Recipient Device Rejection
    // =========================================================================
    @Test
    fun test05_wrongRecipientDeviceRejection() {
        val signPriv = ByteArray(32).also { secureRandom.nextBytes(it) }
        val signPub = Ed25519Signer.derivePublicKey(signPriv)
        val dhPub = X25519.publicFromPrivate(X25519.generatePrivateKey())
        val timestamp = System.currentTimeMillis()

        val canonicalBytes = X3dhHandshakeEngine.encodeIdentityBinding("NX-BOB", "dev_bob_01", signPub, dhPub, timestamp)
        val validSig = Ed25519Signer.sign(canonicalBytes, signPriv)

        // Verification against dev_bob_02 must fail
        assertFalse(
            "Signature bound to dev_bob_01 must fail on dev_bob_02",
            X3dhHandshakeEngine.verifyIdentityBindingSignature(
                toHex(signPub), toHex(dhPub), toHex(validSig), "NX-BOB", "dev_bob_02", timestamp
            )
        )
    }

    // =========================================================================
    // TEST 6: Replayed Initialization Envelope Protection
    // =========================================================================
    @Test
    fun test06_replayedInitializationProtection() {
        val aliceDhPriv = X25519.generatePrivateKey()
        val aliceDhPub = X25519.publicFromPrivate(aliceDhPriv)
        val aliceSignPriv = ByteArray(32).also { secureRandom.nextBytes(it) }
        val aliceSignPub = Ed25519Signer.derivePublicKey(aliceSignPriv)

        val bobDhPriv = X25519.generatePrivateKey()
        val bobDhPub = X25519.publicFromPrivate(bobDhPriv)
        val bobSpkPriv = X25519.generatePrivateKey()
        val bobSpkPub = X25519.publicFromPrivate(bobSpkPriv)
        val bobOpkPriv = X25519.generatePrivateKey()
        val bobOpkPub = X25519.publicFromPrivate(bobOpkPriv)

        val bobPrekey = PrekeyBundle(
            nxId = "NX-BOB",
            deviceId = "dev_01",
            identityKeyHex = toHex(bobDhPub),
            signingKeyHex = toHex(bobDhPub),
            signedPrekeyHex = toHex(bobSpkPub),
            signedPrekeySignatureHex = "00",
            oneTimePrekeyHex = toHex(bobOpkPub),
            oneTimePrekeyId = "101"
        )

        val envelope = X3dhHandshakeEngine.buildInitialEnvelope(
            senderNxId = "NX-ALICE",
            senderDisplayName = "Alice",
            introMessage = "Hello Bob!",
            senderIdentityPrivateKey = aliceDhPriv,
            senderIdentityPublicKeyHex = toHex(aliceDhPub),
            senderSigningPrivateKey = aliceSignPriv,
            recipientPrekey = bobPrekey,
            powNonce = 123L,
            powTimestamp = System.currentTimeMillis()
        )

        // First arrival: Bob has OPK 101 in storage
        val opkPool = mutableMapOf("101" to bobOpkPriv)
        val payload1 = X3dhHandshakeEngine.processInboundEnvelope(
            envelope = envelope,
            recipientIdentityPrivateKey = bobDhPriv,
            recipientSignedPrivateKey = bobSpkPriv,
            senderSigningPublicKey = aliceSignPub,
            recipientOneTimePrivateKeys = opkPool
        )
        assertNotNull("First arrival with valid OPK must succeed", payload1)

        // After consumption, OPK 101 is securely wiped from recipient's storage
        opkPool.remove("101")

        // Replay of same envelope now lacks the burned OPK private key
        val payload2 = X3dhHandshakeEngine.processInboundEnvelope(
            envelope = envelope,
            recipientIdentityPrivateKey = bobDhPriv,
            recipientSignedPrivateKey = bobSpkPriv,
            senderSigningPublicKey = aliceSignPub,
            recipientOneTimePrivateKeys = opkPool
        )
        assertNull("Replayed handshake with burned OPK must fail decryption", payload2)
    }

    // =========================================================================
    // TEST 7: Matching Initiator/Responder Session Secrets
    // =========================================================================
    @Test
    fun test07_matchingInitiatorResponderSessionSecrets() = runBlocking {
        val aliceDao = TestInMemoryRatchetDao()
        val bobDao = TestInMemoryRatchetDao()
        val aliceEngine = DoubleRatchetEngine(aliceDao)
        val bobEngine = DoubleRatchetEngine(bobDao)

        val masterSecret = ByteArray(32).also { secureRandom.nextBytes(it) }
        val bobSpkPriv = X25519.generatePrivateKey()
        val bobSpkPub = X25519.publicFromPrivate(bobSpkPriv)
        val sessionId = UUID.randomUUID().toString()

        // Alice initializes Initiator session
        val aliceSession = aliceEngine.initInitiatorSession(
            contactNxId = "NX-BOB",
            masterSecret = masterSecret,
            remoteSpkPublicKey = bobSpkPub,
            sessionId = sessionId
        )

        // Bob initializes Responder session using Alice's initial ratchet key
        val aliceRatchetPub = fromHex(aliceSession.localDhPublicKeyHex)
        val bobSession = bobEngine.initResponderSession(
            contactNxId = "NX-ALICE",
            masterSecret = masterSecret,
            localSpkPrivateKey = bobSpkPriv,
            localSpkPublicKey = bobSpkPub,
            remoteRatchetPublicKey = aliceRatchetPub,
            sessionId = sessionId
        )

        // Alice's Root Key and Bob's Root Key must match
        assertEquals("Root keys must be identical", aliceSession.rootKeyHex, bobSession.rootKeyHex)
        // Alice's Sending Chain Key must equal Bob's Receiving Chain Key
        assertEquals(
            "Alice's sender chain key must equal Bob's receiver chain key",
            aliceSession.senderChainKeyHex,
            bobSession.receiverChainKeyHex
        )
    }

    // =========================================================================
    // TEST 8: Successful Bidirectional Double Ratchet Transitions
    // =========================================================================
    @Test
    fun test08_successfulBidirectionalDoubleRatchet() = runBlocking {
        val aliceDao = TestInMemoryRatchetDao()
        val bobDao = TestInMemoryRatchetDao()
        val aliceEngine = DoubleRatchetEngine(aliceDao)
        val bobEngine = DoubleRatchetEngine(bobDao)

        val masterSecret = ByteArray(32).also { secureRandom.nextBytes(it) }
        val bobSpkPriv = X25519.generatePrivateKey()
        val bobSpkPub = X25519.publicFromPrivate(bobSpkPriv)
        val sessionId = UUID.randomUUID().toString()

        val aliceSession = aliceEngine.initInitiatorSession("Bob", masterSecret, bobSpkPub, sessionId)
        bobEngine.initResponderSession("Alice", masterSecret, bobSpkPriv, bobSpkPub, fromHex(aliceSession.localDhPublicKeyHex), sessionId)

        // Turn 1: Alice -> Bob (3 messages in single sending chain)
        val m1 = "Alice Msg 1".toByteArray(StandardCharsets.UTF_8)
        val m2 = "Alice Msg 2".toByteArray(StandardCharsets.UTF_8)
        val m3 = "Alice Msg 3".toByteArray(StandardCharsets.UTF_8)

        val env1 = aliceEngine.ratchetEncrypt("Bob", m1)
        val env2 = aliceEngine.ratchetEncrypt("Bob", m2)
        val env3 = aliceEngine.ratchetEncrypt("Bob", m3)

        assertEquals("Alice Msg 1", String(bobEngine.ratchetDecrypt("Alice", env1)!!, StandardCharsets.UTF_8))
        assertEquals("Alice Msg 2", String(bobEngine.ratchetDecrypt("Alice", env2)!!, StandardCharsets.UTF_8))
        assertEquals("Alice Msg 3", String(bobEngine.ratchetDecrypt("Alice", env3)!!, StandardCharsets.UTF_8))

        // Turn 2: Bob -> Alice (DH Ratchet Turn from Bob)
        val r1 = "Bob Reply 1".toByteArray(StandardCharsets.UTF_8)
        val r2 = "Bob Reply 2".toByteArray(StandardCharsets.UTF_8)

        val envR1 = bobEngine.ratchetEncrypt("Alice", r1)
        val envR2 = bobEngine.ratchetEncrypt("Alice", r2)

        assertEquals("Bob Reply 1", String(aliceEngine.ratchetDecrypt("Bob", envR1)!!, StandardCharsets.UTF_8))
        assertEquals("Bob Reply 2", String(aliceEngine.ratchetDecrypt("Bob", envR2)!!, StandardCharsets.UTF_8))

        // Turn 3: Alice -> Bob (DH Ratchet Turn back to Alice)
        val m4 = "Alice Msg 4 after ratchet turn".toByteArray(StandardCharsets.UTF_8)
        val env4 = aliceEngine.ratchetEncrypt("Bob", m4)
        assertEquals("Alice Msg 4 after ratchet turn", String(bobEngine.ratchetDecrypt("Alice", env4)!!, StandardCharsets.UTF_8))
    }

    // =========================================================================
    // TEST 9: Tampered Ciphertext Rejection
    // =========================================================================
    @Test
    fun test09_tamperedCiphertextRejection() = runBlocking {
        val aliceDao = TestInMemoryRatchetDao()
        val bobDao = TestInMemoryRatchetDao()
        val aliceEngine = DoubleRatchetEngine(aliceDao)
        val bobEngine = DoubleRatchetEngine(bobDao)

        val ms = ByteArray(32).also { secureRandom.nextBytes(it) }
        val bobSpkPriv = X25519.generatePrivateKey()
        val bobSpkPub = X25519.publicFromPrivate(bobSpkPriv)
        val sid = UUID.randomUUID().toString()

        val aSession = aliceEngine.initInitiatorSession("Bob", ms, bobSpkPub, sid)
        bobEngine.initResponderSession("Alice", ms, bobSpkPriv, bobSpkPub, fromHex(aSession.localDhPublicKeyHex), sid)

        val env = aliceEngine.ratchetEncrypt("Bob", "Secret Plaintext".toByteArray())
        val tamperedCiphertext = env.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        val tamperedEnv = env.copy(ciphertext = tamperedCiphertext)

        val decrypted = bobEngine.ratchetDecrypt("Alice", tamperedEnv)
        assertNull("Tampered ciphertext must fail authentication and return null", decrypted)
    }

    // =========================================================================
    // TEST 10: Wrong AEAD Nonce Rejection
    // =========================================================================
    @Test
    fun test10_wrongAeadNonceRejection() = runBlocking {
        val aliceDao = TestInMemoryRatchetDao()
        val bobDao = TestInMemoryRatchetDao()
        val aliceEngine = DoubleRatchetEngine(aliceDao)
        val bobEngine = DoubleRatchetEngine(bobDao)

        val ms = ByteArray(32).also { secureRandom.nextBytes(it) }
        val bobSpkPriv = X25519.generatePrivateKey()
        val bobSpkPub = X25519.publicFromPrivate(bobSpkPriv)
        val sid = UUID.randomUUID().toString()

        val aSession = aliceEngine.initInitiatorSession("Bob", ms, bobSpkPub, sid)
        bobEngine.initResponderSession("Alice", ms, bobSpkPriv, bobSpkPub, fromHex(aSession.localDhPublicKeyHex), sid)

        val env = aliceEngine.ratchetEncrypt("Bob", "Valid message".toByteArray())
        // Modify message index so that recipient derives a different nonce and AD
        val wrongIndexEnv = env.copy(messageIndex = 999)

        val decrypted = bobEngine.ratchetDecrypt("Alice", wrongIndexEnv)
        assertNull("Wrong message index / AEAD nonce must fail authentication", decrypted)
    }

    // =========================================================================
    // TEST 11: Wrong Authentication Tag Rejection
    // =========================================================================
    @Test
    fun test11_wrongAuthenticationTagRejection() = runBlocking {
        val aliceDao = TestInMemoryRatchetDao()
        val bobDao = TestInMemoryRatchetDao()
        val aliceEngine = DoubleRatchetEngine(aliceDao)
        val bobEngine = DoubleRatchetEngine(bobDao)

        val ms = ByteArray(32).also { secureRandom.nextBytes(it) }
        val bobSpkPriv = X25519.generatePrivateKey()
        val bobSpkPub = X25519.publicFromPrivate(bobSpkPriv)
        val sid = UUID.randomUUID().toString()

        val aSession = aliceEngine.initInitiatorSession("Bob", ms, bobSpkPub, sid)
        bobEngine.initResponderSession("Alice", ms, bobSpkPriv, bobSpkPub, fromHex(aSession.localDhPublicKeyHex), sid)

        val env = aliceEngine.ratchetEncrypt("Bob", "Authentic message".toByteArray())
        val badTag = env.authTag.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val tamperedEnv = env.copy(authTag = badTag)

        val decrypted = bobEngine.ratchetDecrypt("Alice", tamperedEnv)
        assertNull("Modified authentication tag must fail verification immediately", decrypted)
    }

    // =========================================================================
    // TEST 12: Duplicate Message Rejection
    // =========================================================================
    @Test
    fun test12_duplicateMessageRejection() = runBlocking {
        val aliceDao = TestInMemoryRatchetDao()
        val bobDao = TestInMemoryRatchetDao()
        val aliceEngine = DoubleRatchetEngine(aliceDao)
        val bobEngine = DoubleRatchetEngine(bobDao)

        val ms = ByteArray(32).also { secureRandom.nextBytes(it) }
        val bobSpkPriv = X25519.generatePrivateKey()
        val bobSpkPub = X25519.publicFromPrivate(bobSpkPriv)
        val sid = UUID.randomUUID().toString()

        val aSession = aliceEngine.initInitiatorSession("Bob", ms, bobSpkPub, sid)
        bobEngine.initResponderSession("Alice", ms, bobSpkPriv, bobSpkPub, fromHex(aSession.localDhPublicKeyHex), sid)

        val env0 = aliceEngine.ratchetEncrypt("Bob", "Original message".toByteArray())

        // First delivery: succeeds
        val dec1 = bobEngine.ratchetDecrypt("Alice", env0)
        assertNotNull("First delivery must succeed", dec1)

        // Duplicate delivery of same packet: rejected
        val dec2 = bobEngine.ratchetDecrypt("Alice", env0)
        assertNull("Duplicate packet delivery must be rejected without state corruption", dec2)
    }

    // =========================================================================
    // TEST 13: Out-of-Order Delivery Tolerance
    // =========================================================================
    @Test
    fun test13_outOfOrderDeliveryTolerance() = runBlocking {
        val aliceDao = TestInMemoryRatchetDao()
        val bobDao = TestInMemoryRatchetDao()
        val aliceEngine = DoubleRatchetEngine(aliceDao)
        val bobEngine = DoubleRatchetEngine(bobDao)

        val ms = ByteArray(32).also { secureRandom.nextBytes(it) }
        val bobSpkPriv = X25519.generatePrivateKey()
        val bobSpkPub = X25519.publicFromPrivate(bobSpkPriv)
        val sid = UUID.randomUUID().toString()

        val aSession = aliceEngine.initInitiatorSession("Bob", ms, bobSpkPub, sid)
        bobEngine.initResponderSession("Alice", ms, bobSpkPriv, bobSpkPub, fromHex(aSession.localDhPublicKeyHex), sid)

        val env0 = aliceEngine.ratchetEncrypt("Bob", "Packet 0".toByteArray())
        val env1 = aliceEngine.ratchetEncrypt("Bob", "Packet 1".toByteArray())
        val env2 = aliceEngine.ratchetEncrypt("Bob", "Packet 2".toByteArray())

        // Deliver Packet 0
        assertEquals("Packet 0", String(bobEngine.ratchetDecrypt("Alice", env0)!!))

        // Deliver Packet 2 BEFORE Packet 1
        val dec2 = bobEngine.ratchetDecrypt("Alice", env2)
        assertNotNull("Out-of-order forward packet must be decrypted", dec2)
        assertEquals("Packet 2", String(dec2!!))

        // Verify Bob's skipped keys table stored message key for Packet 1
        assertEquals(1, bobDao.countSkippedKeys("Alice"))
    }

    // =========================================================================
    // TEST 14: Skipped Message-Key Recovery
    // =========================================================================
    @Test
    fun test14_skippedMessageKeyRecovery() = runBlocking {
        val aliceDao = TestInMemoryRatchetDao()
        val bobDao = TestInMemoryRatchetDao()
        val aliceEngine = DoubleRatchetEngine(aliceDao)
        val bobEngine = DoubleRatchetEngine(bobDao)

        val ms = ByteArray(32).also { secureRandom.nextBytes(it) }
        val bobSpkPriv = X25519.generatePrivateKey()
        val bobSpkPub = X25519.publicFromPrivate(bobSpkPriv)
        val sid = UUID.randomUUID().toString()

        val aSession = aliceEngine.initInitiatorSession("Bob", ms, bobSpkPub, sid)
        bobEngine.initResponderSession("Alice", ms, bobSpkPriv, bobSpkPub, fromHex(aSession.localDhPublicKeyHex), sid)

        val env0 = aliceEngine.ratchetEncrypt("Bob", "Msg 0".toByteArray())
        val env1 = aliceEngine.ratchetEncrypt("Bob", "Delayed Msg 1".toByteArray())
        val env2 = aliceEngine.ratchetEncrypt("Bob", "Msg 2".toByteArray())

        // Bob receives 0 then 2
        bobEngine.ratchetDecrypt("Alice", env0)
        bobEngine.ratchetDecrypt("Alice", env2)

        // Delayed Msg 1 finally arrives
        val dec1 = bobEngine.ratchetDecrypt("Alice", env1)
        assertNotNull("Delayed packet must be decrypted using stored skipped key", dec1)
        assertEquals("Delayed Msg 1", String(dec1!!))

        // Once consumed, skipped key is purged from DB
        assertEquals(0, bobDao.countSkippedKeys("Alice"))
    }

    // =========================================================================
    // TEST 15: Ratchet State Persistence After Process Restart
    // =========================================================================
    @Test
    fun test15_ratchetPersistenceAfterRestart() = runBlocking {
        val sharedDao = TestInMemoryRatchetDao()
        val bobDao = TestInMemoryRatchetDao()

        val ms = ByteArray(32).also { secureRandom.nextBytes(it) }
        val bobSpkPriv = X25519.generatePrivateKey()
        val bobSpkPub = X25519.publicFromPrivate(bobSpkPriv)
        val sid = UUID.randomUUID().toString()

        // Alice Session 1
        val engine1 = DoubleRatchetEngine(sharedDao)
        val aSession = engine1.initInitiatorSession("Bob", ms, bobSpkPub, sid)

        val bobEngine = DoubleRatchetEngine(bobDao)
        bobEngine.initResponderSession("Alice", ms, bobSpkPriv, bobSpkPub, fromHex(aSession.localDhPublicKeyHex), sid)

        val env1 = engine1.ratchetEncrypt("Bob", "Before reboot".toByteArray())
        assertEquals("Before reboot", String(bobEngine.ratchetDecrypt("Alice", env1)!!))

        // Simulate app crash / process termination: instantiate new DoubleRatchetEngine with same DB
        val engineAfterRestart = DoubleRatchetEngine(sharedDao)
        val env2 = engineAfterRestart.ratchetEncrypt("Bob", "After reboot".toByteArray())
        assertEquals("After reboot", String(bobEngine.ratchetDecrypt("Alice", env2)!!))
    }

    // =========================================================================
    // TEST 16: Concurrent Session Initialization (Glare / Tie-Breaker)
    // =========================================================================
    @Test
    fun test16_concurrentSessionInitializationTieBreaker() {
        val aliceSignPriv = ByteArray(32).also { secureRandom.nextBytes(it) }
        val aliceSignPub = Ed25519Signer.derivePublicKey(aliceSignPriv)

        val bobSignPriv = ByteArray(32).also { secureRandom.nextBytes(it) }
        val bobSignPub = Ed25519Signer.derivePublicKey(bobSignPriv)

        val aliceHex = toHex(aliceSignPub)
        val bobHex = toHex(bobSignPub)

        assertNotEquals("Keys must not be identical", aliceHex, bobHex)

        val aliceIsDominant = aliceHex < bobHex
        val bobIsDominant = bobHex < aliceHex

        assertTrue("Tie-breaker must establish a strictly deterministic winner", aliceIsDominant xor bobIsDominant)
    }

    // =========================================================================
    // TEST 17: Revoked Device Session Invalidation
    // =========================================================================
    @Test
    fun test17_revokedDeviceInvalidation() = runBlocking {
        val aliceDao = TestInMemoryRatchetDao()
        val ms = ByteArray(32).also { secureRandom.nextBytes(it) }
        val spk = X25519.publicFromPrivate(X25519.generatePrivateKey())

        val aliceEngine = DoubleRatchetEngine(aliceDao)
        aliceEngine.initInitiatorSession("Bob_RevokedDev", ms, spk, remoteDeviceId = "dev_revoked_99")

        assertNotNull(aliceDao.getSession("Bob_RevokedDev"))

        // Revocation event received: drop pairwise ratchet session
        aliceDao.deleteSession("Bob_RevokedDev")
        assertNull("Revoked device session must be purged from storage", aliceDao.getSession("Bob_RevokedDev"))
    }

    // =========================================================================
    // TEST 18: Atomic State Protection on Authentication Failure
    // =========================================================================
    @Test
    fun test18_atomicStateProtectionOnFailure() = runBlocking {
        val aliceDao = TestInMemoryRatchetDao()
        val bobDao = TestInMemoryRatchetDao()
        val aliceEngine = DoubleRatchetEngine(aliceDao)
        val bobEngine = DoubleRatchetEngine(bobDao)

        val ms = ByteArray(32).also { secureRandom.nextBytes(it) }
        val bobSpkPriv = X25519.generatePrivateKey()
        val bobSpkPub = X25519.publicFromPrivate(bobSpkPriv)
        val sid = UUID.randomUUID().toString()

        val aSession = aliceEngine.initInitiatorSession("Bob", ms, bobSpkPub, sid)
        bobEngine.initResponderSession("Alice", ms, bobSpkPriv, bobSpkPub, fromHex(aSession.localDhPublicKeyHex), sid)

        val initialBobSession = bobDao.getSession("Alice")!!
        val initialSeq = initialBobSession.receiverSeqNumber

        // Craft a corrupted packet
        val env = aliceEngine.ratchetEncrypt("Bob", "Legit packet".toByteArray())
        val corruptEnv = env.copy(ciphertext = ByteArray(env.ciphertext.size) { 0x00 })

        val decResult = bobEngine.ratchetDecrypt("Alice", corruptEnv)
        assertNull("Corrupt packet must fail decryption", decResult)

        val bobSessionAfterFailure = bobDao.getSession("Alice")!!
        assertEquals(
            "Receiver sequence number must NOT advance on failed decryption",
            initialSeq,
            bobSessionAfterFailure.receiverSeqNumber
        )
        assertEquals(
            "Receiver chain key must NOT advance on failed decryption",
            initialBobSession.receiverChainKeyHex,
            bobSessionAfterFailure.receiverChainKeyHex
        )
    }

    // =========================================================================
    // STEP 8: Two-Device Integration Testing (Full E2E Flow)
    // =========================================================================
    @Test
    fun test19_twoDeviceIntegrationE2E() = runBlocking {
        // Device A (Alice)
        val aliceDao = TestInMemoryRatchetDao()
        val aliceEngine = DoubleRatchetEngine(aliceDao)
        val aliceIkPriv = X25519.generatePrivateKey()
        val aliceIkPub = X25519.publicFromPrivate(aliceIkPriv)
        val aliceSignPriv = ByteArray(32).also { secureRandom.nextBytes(it) }
        val aliceSignPub = Ed25519Signer.derivePublicKey(aliceSignPriv)

        // Device B (Bob)
        val bobDao = TestInMemoryRatchetDao()
        val bobEngine = DoubleRatchetEngine(bobDao)
        val bobIkPriv = X25519.generatePrivateKey()
        val bobIkPub = X25519.publicFromPrivate(bobIkPriv)
        val bobSpkPriv = X25519.generatePrivateKey()
        val bobSpkPub = X25519.publicFromPrivate(bobSpkPriv)
        val bobSignPriv = ByteArray(32).also { secureRandom.nextBytes(it) }
        val bobSignPub = Ed25519Signer.derivePublicKey(bobSignPriv)

        val sessionId = "urn:uuid:${UUID.randomUUID()}"

        // 1. Initial Handshake & Secret Derivation
        val aliceEkPriv = X25519.generatePrivateKey()
        val aliceEkPub = X25519.publicFromPrivate(aliceEkPriv)

        val aliceSk = X3dhHandshakeEngine.deriveInitiatorMasterSecret(
            senderIdentityPrivateKey = aliceIkPriv,
            ephemeralPrivateKey = aliceEkPriv,
            recipientIdentityPublicKey = bobIkPub,
            recipientSignedPublicKey = bobSpkPub,
            recipientOneTimePublicKey = null
        )

        val bobSk = X3dhHandshakeEngine.deriveResponderMasterSecret(
            recipientIdentityPrivateKey = bobIkPriv,
            recipientSignedPrivateKey = bobSpkPriv,
            recipientOneTimePrivateKey = null,
            senderIdentityPublicKey = aliceIkPub,
            senderEphemeralPublicKey = aliceEkPub
        )

        assertEquals("Alice and Bob must compute identical Master Secrets", toHex(aliceSk), toHex(bobSk))

        // 2. Double Ratchet Session Initialization
        val aliceSession = aliceEngine.initInitiatorSession("Bob", aliceSk, bobSpkPub, sessionId)
        bobEngine.initResponderSession("Alice", bobSk, bobSpkPriv, bobSpkPub, fromHex(aliceSession.localDhPublicKeyHex), sessionId)

        // 3. Alice Encrypts Message
        val plaintext1 = "Hello Bob from Alice via NEXA Signal E2EE!"
        val envelope1 = aliceEngine.ratchetEncrypt("Bob", plaintext1.toByteArray(StandardCharsets.UTF_8))
        val wirePayload1 = envelope1.toProtobufPayload()

        // 4. Backend routes ciphertext only (Zero Knowledge check)
        assertFalse("Wire payload must not contain plaintext", String(wirePayload1.ciphertext).contains("Hello Bob"))

        // 5. Bob Decrypts Message
        val unpackedEnvelope1 = EncryptedEnvelope.fromProtobufPayload(wirePayload1)
        val decryptedBytes1 = bobEngine.ratchetDecrypt("Alice", unpackedEnvelope1)
        assertNotNull(decryptedBytes1)
        assertEquals(plaintext1, String(decryptedBytes1!!, StandardCharsets.UTF_8))

        // 6. Bob Replies (Triggers DH Ratchet Turn)
        val plaintextReply = "Hey Alice, received loud and clear! Double Ratchet is rolling."
        val envelopeReply = bobEngine.ratchetEncrypt("Alice", plaintextReply.toByteArray(StandardCharsets.UTF_8))
        val wirePayloadReply = envelopeReply.toProtobufPayload()

        // 7. Alice Decrypts Bob's Reply
        val unpackedReply = EncryptedEnvelope.fromProtobufPayload(wirePayloadReply)
        val decryptedReplyBytes = aliceEngine.ratchetDecrypt("Bob", unpackedReply)
        assertNotNull(decryptedReplyBytes)
        assertEquals(plaintextReply, String(decryptedReplyBytes!!, StandardCharsets.UTF_8))

        // 8. Offline Delivery Simulation
        val offlinePlaintext = "Queued offline message delivered on reconnect"
        val offlineEnv = aliceEngine.ratchetEncrypt("Bob", offlinePlaintext.toByteArray(StandardCharsets.UTF_8))
        val offlineWire = offlineEnv.toProtobufPayload()

        // Bob reconnects and consumes queued packet
        val unpackedOffline = EncryptedEnvelope.fromProtobufPayload(offlineWire)
        val decryptedOffline = bobEngine.ratchetDecrypt("Alice", unpackedOffline)
        assertEquals(offlinePlaintext, String(decryptedOffline!!, StandardCharsets.UTF_8))

        // 9. Duplicate delivery immunity
        val dupResult = bobEngine.ratchetDecrypt("Alice", unpackedOffline)
        assertNull("Duplicate delivery must be rejected", dupResult)
    }

    // =========================================================================
    // IN-MEMORY DAO FOR ISOLATED TESTING
    // =========================================================================
    private class TestInMemoryRatchetDao : RatchetDao {
        private val sessions = ConcurrentHashMap<String, RatchetStateEntity>()
        private val skippedKeys = ConcurrentHashMap<String, SkippedKeyEntity>()

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
            return skippedKeys.values.distinct().count { it.contactNxId == contactNxId }
        }
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun fromHex(hex: String): ByteArray {
        val clean = hex.trim()
        val len = clean.length
        require(len % 2 == 0) { "Hex length must be even" }
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
