package com.nexa.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.google.crypto.tink.subtle.X25519
import com.nexa.app.core.crypto.DoubleRatchetEngine
import com.nexa.app.core.crypto.Ed25519Signer
import com.nexa.app.core.network.NetworkMonitor
import com.nexa.app.core.qr.QrBitmapGenerator
import com.nexa.app.core.qr.QrPayloadCodec
import com.nexa.app.core.qr.QrPeerPayload
import com.nexa.app.core.qr.QrSecurityValidator
import com.nexa.app.core.qr.QrValidationResult
import com.nexa.app.core.security.CryptoKeystoreManager
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.data.local.dao.ContactDao
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import com.nexa.app.data.remote.api.QrSyncApi
import com.nexa.app.domain.repository.DtnRepository
import com.nexa.app.domain.repository.QrHandshakeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Concrete implementation of the Instant Cryptographic QR Contact Exchange & Day-0 Handshake Engine.
 */
class QrHandshakeRepositoryImpl(
    private val context: Context,
    private val contactDao: ContactDao,
    private val secureStorage: SecureStorage,
    private val keystoreManager: CryptoKeystoreManager,
    private val doubleRatchetEngine: DoubleRatchetEngine,
    private val dtnRepository: DtnRepository,
    private val qrSyncApi: QrSyncApi,
    private val networkMonitor: NetworkMonitor
) : QrHandshakeRepository {

    private val secureRandom = SecureRandom()

    override fun getMyNxId(): String {
        return secureStorage.getActiveNxId() ?: "NX-ME"
    }

    override fun getMyDisplayName(): String {
        return getMyNxId()
    }

    override suspend fun generateMyQrPayload(isEphemeral: Boolean): String = withContext(Dispatchers.Default) {
        val nxId = getMyNxId()
        val displayName = getMyDisplayName()
        val (_, dhPubKeyHex) = getOrCreateIdentityDhKeyPair()
        val (signPrivKey, _) = getOrCreateIdentitySigningKeyPair()
        val blePeerId = getOrCreateBlePeerId()

        val expiresAt = if (isEphemeral) {
            (System.currentTimeMillis() / 1000) + EPHEMERAL_WINDOW_SECONDS
        } else {
            null
        }

        val unsignedPayload = QrPeerPayload(
            nxId = nxId,
            displayName = displayName,
            publicKeyHex = dhPubKeyHex,
            blePeerId = blePeerId,
            expiresAtEpochSec = expiresAt
        )

        QrPayloadCodec.encode(unsignedPayload, signPrivKey)
    }

    override suspend fun generateMyQrBitmap(isEphemeral: Boolean, sizePx: Int): Bitmap = withContext(Dispatchers.Default) {
        val payloadUri = generateMyQrPayload(isEphemeral)
        QrBitmapGenerator.generate(payloadUri, sizePx)
    }

    override suspend fun processScannedQr(payload: QrPeerPayload): Result<QrValidationResult> = withContext(Dispatchers.IO) {
        try {
            // 1. Cryptographic Signature & Expiry Gate
            val validationResult = QrSecurityValidator.validate(payload)
            if (validationResult != QrValidationResult.Valid) {
                return@withContext Result.success(validationResult)
            }

            // 2. Insert into Local SQLCipher Room DB as CONTACT_VERIFIED
            val contact = ContactEntity(
                nxId = payload.nxId,
                displayName = payload.displayName,
                publicKeyHex = payload.publicKeyHex,
                introMessage = "Verified via Instant QR Handshake",
                status = ContactStatus.CONTACT_VERIFIED,
                blePeerId = payload.blePeerId,
                createdAtTimestamp = System.currentTimeMillis()
            )
            contactDao.upsertContact(contact)

            // 3. Seed Double Ratchet Session Locally (Day-0 Offline Trust)
            val (dhPrivKey, dhPubKeyHex) = getOrCreateIdentityDhKeyPair()
            val localPub = fromHex(dhPubKeyHex)
            val remotePub = fromHex(payload.publicKeyHex)
            doubleRatchetEngine.initBilateralSession(payload.nxId, dhPrivKey, localPub, remotePub)

            // 4. Whitelist Peer in Local Bluetooth / DTN Mesh Radio
            dtnRepository.whitelistMeshPeer(payload.blePeerId)

            // 5. Opportunistic Cloud Sync if Online
            val myNxId = getMyNxId()
            if (networkMonitor.isOnline.value) {
                qrSyncApi.syncQrHandshake(
                    scannerNxId = myNxId,
                    scannedNxId = payload.nxId,
                    scannedSignature = payload.signatureHex.orEmpty()
                )
            }

            Result.success(QrValidationResult.Valid)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Key Management & BLE Peer ID Utilities ---

    private fun getOrCreateBlePeerId(): String {
        val prefs = context.getSharedPreferences("nexa_qr_prefs", Context.MODE_PRIVATE)
        val existing = prefs.getString("ble_peer_id", null)
        if (!existing.isNullOrBlank()) return existing

        val bytes = ByteArray(6).also { secureRandom.nextBytes(it) }
        val generated = bytes.joinToString(":") { "%02X".format(it) }
        prefs.edit().putString("ble_peer_id", generated).apply()
        return generated
    }

    private fun getOrCreateIdentityDhKeyPair(): Pair<ByteArray, String> {
        val prefs = context.getSharedPreferences("nexa_handshake_keys", Context.MODE_PRIVATE)
        val privEncHex = prefs.getString("dh_priv_enc", null)
        val privIvHex = prefs.getString("dh_priv_iv", null)
        val pubHex = prefs.getString("dh_pub", null)

        if (!privEncHex.isNullOrBlank() && !privIvHex.isNullOrBlank() && !pubHex.isNullOrBlank()) {
            val decryptedPriv = keystoreManager.decryptWithMasterKey(fromHex(privIvHex), fromHex(privEncHex))
            return Pair(decryptedPriv, pubHex)
        }

        val newPriv = X25519.generatePrivateKey()
        val newPub = X25519.publicFromPrivate(newPriv)
        val newPubHex = toHex(newPub)

        val (iv, encPriv) = keystoreManager.encryptWithMasterKey(newPriv)
        prefs.edit()
            .putString("dh_priv_enc", toHex(encPriv))
            .putString("dh_priv_iv", toHex(iv))
            .putString("dh_pub", newPubHex)
            .apply()

        return Pair(newPriv, newPubHex)
    }

    private fun getOrCreateIdentitySigningKeyPair(): Pair<ByteArray, String> {
        val prefs = context.getSharedPreferences("nexa_handshake_keys", Context.MODE_PRIVATE)
        val privEncHex = prefs.getString("sign_priv_enc", null)
        val privIvHex = prefs.getString("sign_priv_iv", null)
        val pubHex = prefs.getString("sign_pub", null)

        if (!privEncHex.isNullOrBlank() && !privIvHex.isNullOrBlank() && !pubHex.isNullOrBlank()) {
            val decryptedPriv = keystoreManager.decryptWithMasterKey(fromHex(privIvHex), fromHex(privEncHex))
            return Pair(decryptedPriv, pubHex)
        }

        val newPriv = ByteArray(32).also { secureRandom.nextBytes(it) }
        val newPub = Ed25519Signer.derivePublicKey(newPriv)
        val newPubHex = toHex(newPub)

        val (iv, encPriv) = keystoreManager.encryptWithMasterKey(newPriv)
        prefs.edit()
            .putString("sign_priv_enc", toHex(encPriv))
            .putString("sign_priv_iv", toHex(iv))
            .putString("sign_pub", newPubHex)
            .apply()

        return Pair(newPriv, newPubHex)
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
        const val EPHEMERAL_WINDOW_SECONDS = 15 * 60L // 15 Minutes
    }
}
