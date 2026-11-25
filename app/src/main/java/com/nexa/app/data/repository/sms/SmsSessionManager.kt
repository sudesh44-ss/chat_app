package com.nexa.app.data.repository.sms

import android.util.Log
import com.google.crypto.tink.subtle.X25519
import com.nexa.app.core.security.hexToByteArray
import com.nexa.app.core.sms.SmsSessionStatus
import com.nexa.app.core.sms.nx1.Nx1DirectionKeys
import com.nexa.app.core.sms.nx1.Nx1KeySchedule
import com.nexa.app.core.sms.nx1.SmsReplayWindow
import com.nexa.app.data.local.dao.SmsSessionDao
import com.nexa.app.data.local.entity.SmsSessionEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Arrays

/**
 * Owns the independent offline SMS cryptographic session for each contact.
 *
 * Lifecycle:
 *  - Provision (internet available): exchange provisioning public keys via the existing E2EE
 *    control channel, derive + store a versioned session.
 *  - Use (offline): load the stored session, verify not expired/revoked, encrypt/decrypt, and
 *    maintain per-direction counters (nonce + replay protection).
 *  - Rotate: a re-provision bumps [SmsSessionEntity.keyVersion], because counters restart at 0
 *    and a nonce must never be reused under the same key.
 *  - Revoke: permanently blocks SMS for that contact until re-provisioned.
 *
 * The session root is never stored.
 *
 * Fail-closed rules encoded here:
 *  - a session whose stored local provisioning key no longer matches the Keystore-backed key is
 *    treated as unusable (key loss / restored database) instead of deriving garbage keys;
 *  - counters never wrap; an exhausted counter blocks sending;
 *  - key versions never wrap; exhaustion fails closed.
 */
class SmsSessionManager(
    private val sessionDao: SmsSessionDao,
    private val keyVault: SmsKeyVault,
    private val myNxIdProvider: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionValidityMillis: Long = DEFAULT_VALIDITY_MILLIS
) {

    private val allocationMutex = Mutex()

    suspend fun localProvisioningPublicKeyHex(): String = keyVault.localPublicKeyHex()

    suspend fun status(peerNxId: String): SmsSessionStatus {
        val session = sessionDao.getSession(peerNxId)
            ?: return SmsSessionStatus(false, false, false, false, 0, 0L, 0L, null)
        val now = clock()
        val expired = session.expiresAt in 1 until now
        val revoked = session.isRevoked
        val localKeyMismatch = isLocalKeyMismatch(session)
        return SmsSessionStatus(
            exists = true,
            ready = !expired && !revoked && !localKeyMismatch,
            expired = expired,
            revoked = revoked,
            keyVersion = session.keyVersion,
            establishedAt = session.establishedAt,
            expiresAt = session.expiresAt,
            peerProvisioningPublicKeyHex = session.peerProvisioningPublicKeyHex,
            localKeyMismatch = localKeyMismatch
        )
    }

    /**
     * Stores the peer's provisioning public key and (re)establishes the session.
     *
     * Idempotent only while the stored material is unchanged *and* the session is still usable;
     * that path keeps counters and version. Any other path recreates the session with fresh
     * counters, therefore it MUST bump the key version — otherwise a nonce could be reused.
     */
    suspend fun registerPeerProvisioningKey(peerNxId: String, peerPublicKeyHex: String): SmsSessionStatus {
        require(peerPublicKeyHex.length == 64) { "Peer SMS provisioning key must be 32 bytes" }
        val normalized = peerPublicKeyHex.lowercase()
        val localPublic = keyVault.localPublicKeyHex()
        val now = clock()
        val existing = sessionDao.getSession(peerNxId)

        if (existing != null &&
            existing.peerProvisioningPublicKeyHex == normalized &&
            existing.localProvisioningPublicKeyHex == localPublic &&
            !existing.isRevoked &&
            existing.expiresAt > now
        ) {
            // Already established with the same material: keep counters and version.
            return status(peerNxId)
        }

        // Recreate => counters restart at 0 => the key version must change.
        val nextVersion = (existing?.keyVersion ?: 0) + 1
        if (nextVersion > MAX_KEY_VERSION) {
            Log.e(TAG, "SMS key version exhausted for $peerNxId; refusing to reuse a key version")
            throw IllegalStateException("SMS key version space exhausted; re-provisioning is no longer possible")
        }

        val session = SmsSessionEntity(
            peerNxId = peerNxId,
            peerProvisioningPublicKeyHex = normalized,
            localProvisioningPublicKeyHex = localPublic,
            keyVersion = nextVersion,
            establishedAt = now,
            expiresAt = now + sessionValidityMillis,
            isRevoked = false,
            sendCounter = 0L,
            ackSendCounter = 0L,
            recvHighestCounter = -1L,
            recvBitmap = 0L,
            ackRecvHighestCounter = -1L,
            ackRecvBitmap = 0L,
            lastUsedAt = now
        )
        sessionDao.upsertSession(session)
        Log.i(TAG, "Established SMS session for $peerNxId (key version $nextVersion)")
        return status(peerNxId)
    }

    /** @return direction keys, or null when the session is missing, expired, revoked or unusable. */
    suspend fun loadDirectionKeys(peerNxId: String): Nx1DirectionKeys? = allocationMutex.withLock {
        val session = sessionDao.getSession(peerNxId) ?: return@withLock null
        val now = clock()
        if (session.isRevoked) return@withLock null
        if (session.expiresAt in 1 until now) return@withLock null
        if (isLocalKeyMismatch(session)) {
            Log.w(TAG, "SMS session for $peerNxId unusable: local provisioning key no longer matches")
            return@withLock null
        }

        val myNxId = myNxIdProvider()
        val localPrivate = keyVault.localPrivateKey()
        return@withLock try {
            val peerPublic = session.peerProvisioningPublicKeyHex.hexToByteArray()
            val shared = X25519.computeSharedSecret(localPrivate, peerPublic)
            val root = Nx1KeySchedule.deriveSessionRoot(shared, myNxId, peerNxId, session.keyVersion)
            val keys = Nx1KeySchedule.deriveDirectionKeys(root, myNxId, peerNxId, session.keyVersion)
            Arrays.fill(shared, 0.toByte())
            Arrays.fill(root, 0.toByte())
            keys
        } catch (e: Exception) {
            Log.w(TAG, "Unable to derive SMS session keys for $peerNxId: ${e.javaClass.simpleName}")
            null
        } finally {
            Arrays.fill(localPrivate, 0.toByte())
        }
    }

    /**
     * Allocates the next outbound message counter (unique per key/direction).
     * @return null when the session is unusable or the counter space is exhausted (never wraps).
     */
    suspend fun allocateSendCounter(peerNxId: String): Long? = allocationMutex.withLock {
        val session = sessionDao.getSession(peerNxId) ?: return@withLock null
        if (!isUsableForAllocation(session)) return@withLock null
        val counter = session.sendCounter
        if (counter >= MAX_COUNTER) {
            Log.e(TAG, "SMS send counter exhausted for $peerNxId; refusing to reuse a nonce")
            return@withLock null
        }
        sessionDao.updateSendCounter(peerNxId, counter + 1, clock())
        return@withLock counter
    }

    suspend fun allocateAckCounter(peerNxId: String): Long? = allocationMutex.withLock {
        val session = sessionDao.getSession(peerNxId) ?: return@withLock null
        if (!isUsableForAllocation(session)) return@withLock null
        val counter = session.ackSendCounter
        if (counter >= MAX_COUNTER) {
            Log.e(TAG, "SMS ACK counter exhausted for $peerNxId; refusing to reuse a nonce")
            return@withLock null
        }
        sessionDao.updateAckSendCounter(peerNxId, counter + 1, clock())
        return@withLock counter
    }

    /** Applies replay protection for an inbound message counter. */
    suspend fun acceptInboundCounter(peerNxId: String, counter: Long): Boolean = allocationMutex.withLock {
        val session = sessionDao.getSession(peerNxId) ?: return@withLock false
        if (session.isRevoked) return@withLock false
        val window = SmsReplayWindow(session.recvHighestCounter, session.recvBitmap)
        if (!window.accept(counter)) {
            Log.w(TAG, "Rejected replayed SMS packet counter=$counter from $peerNxId")
            return@withLock false
        }
        sessionDao.updateReceiveWindow(peerNxId, window.snapshotHighest(), window.snapshotBitmap(), clock())
        true
    }

    /** Applies replay protection for an inbound ACK counter. */
    suspend fun acceptInboundAckCounter(peerNxId: String, counter: Long): Boolean = allocationMutex.withLock {
        val session = sessionDao.getSession(peerNxId) ?: return@withLock false
        if (session.isRevoked) return@withLock false
        val window = SmsReplayWindow(session.ackRecvHighestCounter, session.ackRecvBitmap)
        if (!window.accept(counter)) {
            Log.w(TAG, "Rejected replayed SMS ACK counter=$counter from $peerNxId")
            return@withLock false
        }
        sessionDao.updateAckReceiveWindow(peerNxId, window.snapshotHighest(), window.snapshotBitmap(), clock())
        true
    }

    suspend fun revoke(peerNxId: String) {
        sessionDao.revokeSession(peerNxId, clock())
        Log.i(TAG, "Revoked SMS session for $peerNxId")
    }

    suspend fun fingerprintForLog(peerNxId: String): String {
        val session = sessionDao.getSession(peerNxId) ?: return "none"
        return "v${session.keyVersion}:${session.peerProvisioningPublicKeyHex.take(8)}"
    }

    private suspend fun isLocalKeyMismatch(session: SmsSessionEntity): Boolean {
        val localPublic = try {
            keyVault.localPublicKeyHex()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read local SMS provisioning key: ${e.javaClass.simpleName}")
            return true
        }
        return !localPublic.equals(session.localProvisioningPublicKeyHex, ignoreCase = true)
    }

    private suspend fun isUsableForAllocation(session: SmsSessionEntity): Boolean {
        if (session.isRevoked) return false
        if (session.expiresAt in 1 until clock()) return false
        if (session.sendCounter < 0 || session.ackSendCounter < 0) return false
        // A restored database without the original KeyStore key cannot produce usable keys, so
        // it must not consume counters (and therefore nonces) for a session it cannot encrypt in.
        if (isLocalKeyMismatch(session)) {
            Log.w(TAG, "SMS session for ${session.peerNxId} unusable: local provisioning key missing")
            return false
        }
        return true
    }

    companion object {
        private const val TAG = "SmsSessionManager"
        const val DEFAULT_VALIDITY_MILLIS = 30L * 24L * 60L * 60L * 1000L

        /** Highest usable counter value; the next value would overflow a signed 64-bit counter. */
        const val MAX_COUNTER = Long.MAX_VALUE - 2

        /** Highest usable key version (uint16 range, as encoded in the packet header). */
        const val MAX_KEY_VERSION = 0xFFFF
    }
}
