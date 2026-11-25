package com.nexa.app

import com.google.crypto.tink.subtle.X25519
import com.nexa.app.core.security.hexToByteArray
import com.nexa.app.core.security.toHex
import com.nexa.app.core.sms.SmsConversationGuard
import com.nexa.app.core.sms.nx1.Nx1Cipher
import com.nexa.app.core.sms.nx1.Nx1Codec
import com.nexa.app.core.sms.nx1.Nx1DecryptFailure
import com.nexa.app.core.sms.nx1.Nx1DecryptResult
import com.nexa.app.core.sms.nx1.Nx1DirectionKeys
import com.nexa.app.core.sms.nx1.Nx1KeySchedule
import com.nexa.app.core.sms.nx1.Nx1MessagePacket
import com.nexa.app.core.sms.nx1.Nx1ParseResult
import com.nexa.app.data.local.dao.SmsSessionDao
import com.nexa.app.data.local.entity.SmsSessionEntity
import com.nexa.app.data.repository.sms.SmsKeyVault
import com.nexa.app.data.repository.sms.SmsSessionManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Module 3 — key-path verification (persistence, restart, rotation, replay, exhaustion).
 *
 * These tests exercise the real [SmsSessionManager] logic against in-memory implementations of
 * the Room DAO and the Keystore-backed vault. That isolates Android from the key lifecycle so
 * restart/rotation/replay behaviour is actually verified rather than assumed.
 *
 * What is NOT covered here (and is reported as such): AndroidKeyStore wrapping, SQLCipher at
 * rest, and real process death. Those require a device (see the instrumentation test).
 */
class Module3SmsKeyPathTests {

    private val aliceNxId = "NX-ALICE-0001"
    private val bobNxId = "NX-BOB-0002"

    // ------------------------------------------------------------------ fakes

    private class FakeSmsSessionDao : SmsSessionDao {
        val sessions = LinkedHashMap<String, SmsSessionEntity>()

        override suspend fun getSession(peerNxId: String): SmsSessionEntity? = sessions[peerNxId]

        override suspend fun upsertSession(session: SmsSessionEntity): Long {
            sessions[session.peerNxId] = session
            return 1L
        }

        override suspend fun updateSendCounter(peerNxId: String, counter: Long, now: Long): Int {
            val s = sessions[peerNxId] ?: return 0
            sessions[peerNxId] = s.copy(sendCounter = counter, lastUsedAt = now)
            return 1
        }

        override suspend fun updateAckSendCounter(peerNxId: String, counter: Long, now: Long): Int {
            val s = sessions[peerNxId] ?: return 0
            sessions[peerNxId] = s.copy(ackSendCounter = counter, lastUsedAt = now)
            return 1
        }

        override suspend fun updateReceiveWindow(peerNxId: String, highest: Long, bitmap: Long, now: Long): Int {
            val s = sessions[peerNxId] ?: return 0
            sessions[peerNxId] = s.copy(recvHighestCounter = highest, recvBitmap = bitmap, lastUsedAt = now)
            return 1
        }

        override suspend fun updateAckReceiveWindow(peerNxId: String, highest: Long, bitmap: Long, now: Long): Int {
            val s = sessions[peerNxId] ?: return 0
            sessions[peerNxId] = s.copy(ackRecvHighestCounter = highest, ackRecvBitmap = bitmap, lastUsedAt = now)
            return 1
        }

        override suspend fun revokeSession(peerNxId: String, now: Long): Int {
            val s = sessions[peerNxId] ?: return 0
            sessions[peerNxId] = s.copy(isRevoked = true, lastUsedAt = now)
            return 1
        }

        override suspend fun deleteSession(peerNxId: String): Int =
            if (sessions.remove(peerNxId) != null) 1 else 0

        override suspend fun getAllSessions(): List<SmsSessionEntity> = sessions.values.toList()
    }

    private class FakeKeyVault : SmsKeyVault {
        var privateKey: ByteArray = X25519.generatePrivateKey()
            private set

        override suspend fun localPublicKeyHex(): String = X25519.publicFromPrivate(privateKey).toHex()

        override suspend fun localPrivateKey(): ByteArray = privateKey.copyOf()

        fun rotate(): String {
            privateKey = X25519.generatePrivateKey()
            return X25519.publicFromPrivate(privateKey).toHex()
        }
    }

    private class PeerIdentity {
        val privateKey: ByteArray = X25519.generatePrivateKey()
        val publicKeyHex: String = X25519.publicFromPrivate(privateKey).toHex()
    }

    private fun manager(
        dao: SmsSessionDao,
        vault: SmsKeyVault,
        clock: () -> Long,
        myNxId: String = aliceNxId
    ) = SmsSessionManager(sessionDao = dao, keyVault = vault, myNxIdProvider = { myNxId }, clock = clock)

    private fun peerDirectionKeys(peer: PeerIdentity, vault: SmsKeyVault, keyVersion: Int): Nx1DirectionKeys {
        val alicePub = runBlocking { vault.localPublicKeyHex() }.hexToByteArray()
        val shared = X25519.computeSharedSecret(peer.privateKey, alicePub)
        val root = Nx1KeySchedule.deriveSessionRoot(shared, bobNxId, aliceNxId, keyVersion)
        return Nx1KeySchedule.deriveDirectionKeys(root, bobNxId, aliceNxId, keyVersion)
    }

    private fun encryptedMessage(
        keys: Nx1DirectionKeys,
        counter: Long,
        messageId: String,
        text: String = "offline hello"
    ): ByteArray = Nx1Cipher.encryptPart(
        keys = keys,
        counter = counter,
        messageId = messageId,
        senderNxId = aliceNxId,
        recipientNxId = bobNxId,
        plaintext = text.toByteArray(StandardCharsets.UTF_8)
    )

    // ------------------------------------------------- restart / recovery

    @Test
    fun sessionKeysAreRecoveredAfterSimulatedProcessDeath() = runBlocking {
        val dao = FakeSmsSessionDao()
        val vault = FakeKeyVault()
        val peer = PeerIdentity()
        var now = 1_000_000L

        val firstProcess = manager(dao, vault, { now })
        firstProcess.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex)
        val keys = firstProcess.loadDirectionKeys(bobNxId)!!
        val counter = firstProcess.allocateSendCounter(bobNxId)!!
        val messageId = Nx1Codec.newMessageId()
        val payload = encryptedMessage(keys, counter, messageId)
        keys.wipe()

        // Process death: a brand-new manager, same persisted database and same KeyStore key.
        now += 60_000
        val secondProcess = manager(dao, vault, { now })
        val recovered = secondProcess.loadDirectionKeys(bobNxId)!!

        assertArrayEquals(keysOf(firstProcess, vault, peer), keysOf(secondProcess, vault, peer))
        val recipient = peerDirectionKeys(peer, vault, 1)
        val decrypted = Nx1Cipher.decryptPart(recipient, messageId, aliceNxId, bobNxId, payload)
        assertTrue("recovered session must decrypt pre-restart ciphertext", decrypted is Nx1DecryptResult.Success)
        assertEquals("offline hello", String((decrypted as Nx1DecryptResult.Success).plaintext, StandardCharsets.UTF_8))
        recovered.wipe()
        recipient.wipe()
    }

    private suspend fun keysOf(m: SmsSessionManager, vault: SmsKeyVault, peer: PeerIdentity): ByteArray {
        val k = m.loadDirectionKeys(bobNxId)!!
        val copy = k.sendingKey.copyOf()
        k.wipe()
        return copy
    }

    @Test
    fun sendCountersPersistAndKeepIncreasingAcrossRestart() = runBlocking {
        val dao = FakeSmsSessionDao()
        val vault = FakeKeyVault()
        val peer = PeerIdentity()
        var now = 2_000_000L

        val firstProcess = manager(dao, vault, { now })
        firstProcess.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex)
        val counters = (0 until 3).map { firstProcess.allocateSendCounter(bobNxId)!! }
        assertEquals(listOf(0L, 1L, 2L), counters)

        now += 5_000
        val secondProcess = manager(dao, vault, { now })
        val afterRestart = secondProcess.allocateSendCounter(bobNxId)!!
        assertEquals("counter must continue, never restart at 0", 3L, afterRestart)
        assertEquals("persisted counter must already point at the next value", 4L, dao.sessions[bobNxId]!!.sendCounter)
    }

    @Test
    fun countersProduceUniqueNonces() = runBlocking {
        val dao = FakeSmsSessionDao()
        val vault = FakeKeyVault()
        val peer = PeerIdentity()
        val m = manager(dao, vault, { 3_000_000L })
        m.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex)

        val nonces = (0 until 50).map {
            val counter = m.allocateSendCounter(bobNxId)!!
            Nx1KeySchedule.deriveNonce(1, counter).toList()
        }
        assertEquals("every nonce must be distinct", 50, nonces.toSet().size)
    }

    @Test
    fun messageAndAckCountersAreIndependent() = runBlocking {
        val dao = FakeSmsSessionDao()
        val vault = FakeKeyVault()
        val peer = PeerIdentity()
        val m = manager(dao, vault, { 4_000_000L })
        m.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex)

        assertEquals(0L, m.allocateSendCounter(bobNxId))
        assertEquals(0L, m.allocateAckCounter(bobNxId))
        assertEquals(1L, m.allocateSendCounter(bobNxId))
        assertEquals(1L, m.allocateAckCounter(bobNxId))
        assertEquals(2L, dao.sessions[bobNxId]!!.sendCounter)
        assertEquals(2L, dao.sessions[bobNxId]!!.ackSendCounter)
    }

    @Test
    fun concurrentAllocationsNeverCollide() = runBlocking {
        val dao = FakeSmsSessionDao()
        val vault = FakeKeyVault()
        val peer = PeerIdentity()
        val m = manager(dao, vault, { 5_000_000L })
        m.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex)

        val allocated = (1..32).map { async { m.allocateSendCounter(bobNxId) } }.awaitAll()
        assertTrue(allocated.all { it != null })
        assertEquals("32 concurrent sends must get 32 distinct counters", 32, allocated.toSet().size)
        assertEquals(32L, dao.sessions[bobNxId]!!.sendCounter)
    }

    // ------------------------------------------------- replay persistence

    @Test
    fun replayWindowSurvivesRestart() = runBlocking {
        val dao = FakeSmsSessionDao()
        val vault = FakeKeyVault()
        val peer = PeerIdentity()
        var now = 6_000_000L
        val firstProcess = manager(dao, vault, { now })
        firstProcess.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex)

        assertTrue(firstProcess.acceptInboundCounter(bobNxId, 3))
        assertTrue(firstProcess.acceptInboundCounter(bobNxId, 5))

        val secondProcess = manager(dao, vault, { now })
        assertFalse("already-seen counter must stay rejected after restart", secondProcess.acceptInboundCounter(bobNxId, 5))
        assertFalse("out-of-window duplicate must stay rejected", secondProcess.acceptInboundCounter(bobNxId, 3))
        assertTrue("gap counter is still acceptable", secondProcess.acceptInboundCounter(bobNxId, 4))
        assertTrue("new counter is acceptable", secondProcess.acceptInboundCounter(bobNxId, 6))
    }

    @Test
    fun ackReplayWindowIsSeparateFromMessageWindow() = runBlocking {
        val dao = FakeSmsSessionDao()
        val vault = FakeKeyVault()
        val peer = PeerIdentity()
        val m = manager(dao, vault, { 7_000_000L })
        m.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex)

        assertTrue(m.acceptInboundCounter(bobNxId, 0))
        assertTrue("same counter in the ACK space is independent", m.acceptInboundAckCounter(bobNxId, 0))
        assertFalse(m.acceptInboundAckCounter(bobNxId, 0))
    }

    // ------------------------------------------------- rotation / expiry / revocation

    @Test
    fun reprovisionWithNewPeerKeyBumpsVersionAndInvalidatesOldCiphertext() = runBlocking {
        val dao = FakeSmsSessionDao()
        val vault = FakeKeyVault()
        val oldPeer = PeerIdentity()
        val newPeer = PeerIdentity()
        var now = 8_000_000L
        val m = manager(dao, vault, { now })
        m.registerPeerProvisioningKey(bobNxId, oldPeer.publicKeyHex)

        val keysV1 = m.loadDirectionKeys(bobNxId)!!
        val messageId = Nx1Codec.newMessageId()
        val payloadV1 = encryptedMessage(keysV1, 0, messageId)
        keysV1.wipe()

        val newStatus = m.registerPeerProvisioningKey(bobNxId, newPeer.publicKeyHex)
        assertEquals(2, newStatus.keyVersion)

        val keysV2 = m.loadDirectionKeys(bobNxId)!!
        assertEquals(2, keysV2.keyVersion)
        val stale = Nx1Cipher.decryptPart(keysV2, messageId, aliceNxId, bobNxId, payloadV1)
        assertTrue(
            "ciphertext from the old key version must not decrypt",
            stale is Nx1DecryptResult.Failure && stale.reason == Nx1DecryptFailure.KEY_VERSION_MISMATCH
        )
        keysV2.wipe()
    }

    @Test
    fun expiryThenReprovisionBumpsVersionInsteadOfReusingNonces() = runBlocking {
        val dao = FakeSmsSessionDao()
        val vault = FakeKeyVault()
        val peer = PeerIdentity()
        var now = 9_000_000L
        val m = manager(dao, vault, { now })
        m.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex)
        assertEquals(0L, m.allocateSendCounter(bobNxId))

        now += SmsSessionManager.DEFAULT_VALIDITY_MILLIS + 1
        val refreshed = m.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex)
        assertEquals("recreated session must not reuse the old key version (nonce reuse)", 2, refreshed.keyVersion)
        assertEquals("counters restart at 0 under the new key version", 0L, m.allocateSendCounter(bobNxId))
        assertTrue(refreshed.ready)
    }

    @Test
    fun regeneratedLocalKeyBumpsVersion() = runBlocking {
        val dao = FakeSmsSessionDao()
        val vault = FakeKeyVault()
        val peer = PeerIdentity()
        val m = manager(dao, vault, { 10_000_000L })
        m.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex)

        vault.rotate()
        val status = m.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex)
        assertEquals(2, status.keyVersion)
        assertTrue(status.ready)
        assertFalse(status.localKeyMismatch)
    }

    @Test
    fun lostLocalKeyMakesSessionUnusableRatherThanDerivingWrongKeys() = runBlocking {
        val dao = FakeSmsSessionDao()
        val vault = FakeKeyVault()
        val peer = PeerIdentity()
        val m = manager(dao, vault, { 11_000_000L })
        m.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex)

        // Simulate a restored database whose original private key is gone from the KeyStore.
        vault.rotate()

        val status = m.status(bobNxId)
        assertTrue(status.exists)
        assertFalse("mismatched local key must not be reported ready", status.ready)
        assertTrue(status.localKeyMismatch)
        assertNull(m.loadDirectionKeys(bobNxId))
        assertNull(m.allocateSendCounter(bobNxId))
    }

    @Test
    fun revokedSessionRefusesKeysCountersAndInbound() = runBlocking {
        val dao = FakeSmsSessionDao()
        val vault = FakeKeyVault()
        val peer = PeerIdentity()
        val m = manager(dao, vault, { 12_000_000L })
        m.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex)
        m.revoke(bobNxId)

        val status = m.status(bobNxId)
        assertTrue(status.revoked)
        assertFalse(status.ready)
        assertNull(m.loadDirectionKeys(bobNxId))
        assertNull(m.allocateSendCounter(bobNxId))
        assertNull(m.allocateAckCounter(bobNxId))
        assertFalse(m.acceptInboundCounter(bobNxId, 0))
        assertFalse(m.acceptInboundAckCounter(bobNxId, 0))
    }

    @Test
    fun expiredSessionRefusesKeysAndCounters() = runBlocking {
        val dao = FakeSmsSessionDao()
        val vault = FakeKeyVault()
        val peer = PeerIdentity()
        var now = 13_000_000L
        val m = manager(dao, vault, { now })
        m.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex)

        now += SmsSessionManager.DEFAULT_VALIDITY_MILLIS + 1
        val status = m.status(bobNxId)
        assertTrue(status.expired)
        assertFalse(status.ready)
        assertNull(m.loadDirectionKeys(bobNxId))
        assertNull(m.allocateSendCounter(bobNxId))
    }

    @Test
    fun missingSessionIsFailClosed() = runBlocking {
        val dao = FakeSmsSessionDao()
        val vault = FakeKeyVault()
        val m = manager(dao, vault, { 14_000_000L })
        assertFalse(m.status(bobNxId).exists)
        assertNull(m.loadDirectionKeys(bobNxId))
        assertNull(m.allocateSendCounter(bobNxId))
        assertNull(m.allocateAckCounter(bobNxId))
        assertFalse(m.acceptInboundCounter(bobNxId, 0))
    }

    // ------------------------------------------------- exhaustion (fail closed)

    @Test
    fun exhaustedSendCounterFailsClosed() = runBlocking {
        val dao = FakeSmsSessionDao()
        val vault = FakeKeyVault()
        val peer = PeerIdentity()
        val m = manager(dao, vault, { 15_000_000L })
        m.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex)

        val session = dao.sessions[bobNxId]!!
        dao.sessions[bobNxId] = session.copy(sendCounter = SmsSessionManager.MAX_COUNTER)

        assertNull("counter must never wrap", m.allocateSendCounter(bobNxId))
        assertEquals(SmsSessionManager.MAX_COUNTER, dao.sessions[bobNxId]!!.sendCounter)
    }

    @Test
    fun exhaustedAckCounterFailsClosed() = runBlocking {
        val dao = FakeSmsSessionDao()
        val vault = FakeKeyVault()
        val peer = PeerIdentity()
        val m = manager(dao, vault, { 16_000_000L })
        m.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex)

        dao.sessions[bobNxId] = dao.sessions[bobNxId]!!.copy(ackSendCounter = SmsSessionManager.MAX_COUNTER)
        assertNull(m.allocateAckCounter(bobNxId))
    }

    @Test
    fun exhaustedKeyVersionSpaceFailsClosedInsteadOfReusingAVersion() = runBlocking {
        val dao = FakeSmsSessionDao()
        val vault = FakeKeyVault()
        val peer = PeerIdentity()
        var now = 17_000_000L
        val m = manager(dao, vault, { now })
        m.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex)

        dao.sessions[bobNxId] = dao.sessions[bobNxId]!!.copy(keyVersion = SmsSessionManager.MAX_KEY_VERSION)
        now += SmsSessionManager.DEFAULT_VALIDITY_MILLIS + 1

        try {
            m.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex)
            org.junit.Assert.fail("key version exhaustion must fail closed")
        } catch (expected: IllegalStateException) {
            assertEquals(SmsSessionManager.MAX_KEY_VERSION, dao.sessions[bobNxId]!!.keyVersion)
        }
    }

    // ------------------------------------------------- ciphertext integrity

    @Test
    fun tamperedCiphertextAndWrongSenderAreRejected() {
        val dao = FakeSmsSessionDao()
        val vault = FakeKeyVault()
        val peer = PeerIdentity()
        val m = manager(dao, vault, { 18_000_000L })
        runBlocking { m.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex) }

        val aliceKeys = runBlocking { m.loadDirectionKeys(bobNxId)!! }
        val bobKeys = peerDirectionKeys(peer, vault, 1)
        val messageId = Nx1Codec.newMessageId()
        val payload = encryptedMessage(aliceKeys, 0, messageId)
        aliceKeys.wipe()

        val tampered = payload.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0xFF).toByte() }
        val tamperResult = Nx1Cipher.decryptPart(bobKeys, messageId, aliceNxId, bobNxId, tampered)
        assertTrue(tamperResult is Nx1DecryptResult.Failure)
        assertEquals(Nx1DecryptFailure.AUTHENTICATION_FAILED, (tamperResult as Nx1DecryptResult.Failure).reason)

        val wrongMessageId = Nx1Cipher.decryptPart(bobKeys, Nx1Codec.newMessageId(), aliceNxId, bobNxId, payload)
        assertTrue("message id is bound in the AAD", wrongMessageId is Nx1DecryptResult.Failure)

        val wrongSender = Nx1Cipher.decryptPart(bobKeys, messageId, "NX-CAROL-9999", bobNxId, payload)
        assertTrue("sender identity is bound in the AAD", wrongSender is Nx1DecryptResult.Failure)

        val wrongRecipient = Nx1Cipher.decryptPart(bobKeys, messageId, aliceNxId, "NX-CAROL-9999", payload)
        assertTrue("recipient identity is bound in the AAD", wrongRecipient is Nx1DecryptResult.Failure)

        bobKeys.wipe()
    }

    @Test
    fun roundTripAcrossRecoveredSessionUsesExpectedCounter() = runBlocking {
        val dao = FakeSmsSessionDao()
        val vault = FakeKeyVault()
        val peer = PeerIdentity()
        val m = manager(dao, vault, { 19_000_000L })
        m.registerPeerProvisioningKey(bobNxId, peer.publicKeyHex)
        val aliceKeys = m.loadDirectionKeys(bobNxId)!!
        val bobKeys = peerDirectionKeys(peer, vault, 1)
        val messageId = Nx1Codec.newMessageId()
        val counter = m.allocateSendCounter(bobNxId)!!
        val payload = encryptedMessage(aliceKeys, counter, messageId)

        val token = Nx1Codec.encodeMessage(
            Nx1MessagePacket(messageId = messageId, partIndex = 0, totalParts = 1, payload = payload)
        )
        val parsed = Nx1Codec.parse(token) as Nx1ParseResult.Message
        val decrypted = Nx1Cipher.decryptPart(bobKeys, parsed.packet.messageId, aliceNxId, bobNxId, parsed.packet.payload)
        assertEquals(counter, (decrypted as Nx1DecryptResult.Success).counter)
        aliceKeys.wipe()
        bobKeys.wipe()
    }

    // ------------------------------------------------- group protection (business rule)

    @Test
    fun groupConversationsAreRecognisedByTheSharedGuard() {
        assertFalse(SmsConversationGuard.isGroupConversation("${aliceNxId}_${bobNxId}", bobNxId))
        assertTrue(SmsConversationGuard.isGroupConversation("group_1234", bobNxId))
        assertTrue(SmsConversationGuard.isGroupConversation("GROUP:team", bobNxId))
        assertTrue(SmsConversationGuard.isGroupConversation("${aliceNxId}_${bobNxId}_carol", bobNxId))
        assertTrue(SmsConversationGuard.isGroupConversation(null, "GROUP-NX-1"))
        assertFalse(SmsConversationGuard.isGroupConversation("", "NX-BOB-0002"))
    }

    @Test
    fun keyVersionIsEncodedInNonceAndDiffersAfterRotation() {
        val v1 = Nx1KeySchedule.deriveNonce(1, 0)
        val v2 = Nx1KeySchedule.deriveNonce(2, 0)
        assertNotEquals(v1.toList(), v2.toList())
    }
}
