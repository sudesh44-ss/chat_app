package com.nexa.app

import com.nexa.app.core.crypto.Ed25519Signer
import com.nexa.app.core.qr.QrPayloadCodec
import com.nexa.app.core.qr.QrPeerPayload
import com.nexa.app.core.qr.QrSecurityValidator
import com.nexa.app.core.qr.QrValidationResult
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Unit test suite verifying the cryptographic integrity, anti-tampering,
 * anti-stalking 15-minute expiration, and wire codec of the Instant QR Handshake Engine.
 */
class QrHandshakeUnitTests {

    private val secureRandom = SecureRandom()

    private fun generateEd25519KeyPair(): Pair<ByteArray, ByteArray> {
        val privKey = ByteArray(32).also { secureRandom.nextBytes(it) }
        val pubKey = Ed25519Signer.derivePublicKey(privKey)
        return privKey to pubKey
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun testQrPayloadCodecRoundtripPermanentProfile() {
        val (privKey, pubKey) = generateEd25519KeyPair()
        val pubKeyHex = pubKey.toHex()

        val originalPayload = QrPeerPayload(
            nxId = "NX-8821-X9A",
            displayName = "Alice Crypto",
            publicKeyHex = pubKeyHex,
            blePeerId = "A1B2C3D4E5F6",
            expiresAtEpochSec = null
        )

        // Encode to nx://add URI signed with private key
        val encodedUri = QrPayloadCodec.encode(originalPayload, privKey)
        assertTrue("URI must use 'nx' scheme", encodedUri.startsWith("nx://add?"))
        assertTrue("URI must contain ID parameter", encodedUri.contains("id=NX-8821-X9A"))
        assertTrue("URI must contain Display Name parameter", encodedUri.contains("name=Alice+Crypto") || encodedUri.contains("name=Alice%20Crypto"))

        // Decode back to QrPeerPayload
        val decodedPayload = QrPayloadCodec.decode(encodedUri)
        assertNotNull("Decoded payload must not be null", decodedPayload)
        assertEquals("NX-ID must match", originalPayload.nxId, decodedPayload?.nxId)
        assertEquals("Display Name must match", originalPayload.displayName, decodedPayload?.displayName)
        assertEquals("Public Key must match", originalPayload.publicKeyHex, decodedPayload?.publicKeyHex)
        assertEquals("BLE Peer ID must match", originalPayload.blePeerId, decodedPayload?.blePeerId)
        assertNull("Permanent QR must have null expiration", decodedPayload?.expiresAtEpochSec)
        assertNotNull("Signature must be present and decoded", decodedPayload?.signatureHex)
        assertEquals("Signature must be strictly 128 hex chars (64 bytes)", 128, decodedPayload?.signatureHex?.length)

        // Validate cryptographically
        val validation = QrSecurityValidator.validate(decodedPayload!!)
        assertEquals("Valid signed payload must pass validation", QrValidationResult.Valid, validation)
    }

    @Test
    fun testQrPayloadCodecRoundtripEphemeralAntiStalking() {
        val (privKey, pubKey) = generateEd25519KeyPair()
        val pubKeyHex = pubKey.toHex()
        val expirySec = (System.currentTimeMillis() / 1000) + 900 // 15 minutes in the future

        val originalPayload = QrPeerPayload(
            nxId = "NX-4310-Z8P",
            displayName = "Bob Ephemeral",
            publicKeyHex = pubKeyHex,
            blePeerId = "F6E5D4C3B2A1",
            expiresAtEpochSec = expirySec
        )

        val encodedUri = QrPayloadCodec.encode(originalPayload, privKey)
        assertTrue("URI must contain exp parameter", encodedUri.contains("exp=$expirySec"))

        val decodedPayload = QrPayloadCodec.decode(encodedUri)
        assertNotNull("Decoded payload must not be null", decodedPayload)
        assertEquals("Expiry timestamp must match exactly", expirySec, decodedPayload?.expiresAtEpochSec)

        // Validate before expiration
        val validCheck = QrSecurityValidator.validate(
            decodedPayload!!,
            currentEpochSec = expirySec - 60
        )
        assertEquals("Payload must be valid prior to expiration", QrValidationResult.Valid, validCheck)
    }

    @Test
    fun testSecurityValidatorRejectsTamperedDisplayName() {
        val (privKey, pubKey) = generateEd25519KeyPair()
        val pubKeyHex = pubKey.toHex()

        val legitPayload = QrPeerPayload(
            nxId = "NX-SAFE-USER",
            displayName = "Real Identity",
            publicKeyHex = pubKeyHex,
            blePeerId = "001122334455",
            expiresAtEpochSec = null
        )

        val encodedUri = QrPayloadCodec.encode(legitPayload, privKey)
        val decodedPayload = QrPayloadCodec.decode(encodedUri)!!

        // Attacker tampers with the display name in the payload
        val tamperedPayload = decodedPayload.copy(displayName = "Malicious Impostor")

        val result = QrSecurityValidator.validate(tamperedPayload)
        assertEquals(
            "Tampered display name must cause Ed25519 signature mismatch",
            QrValidationResult.SignatureMismatch,
            result
        )
    }

    @Test
    fun testSecurityValidatorRejectsTamperedNxId() {
        val (privKey, pubKey) = generateEd25519KeyPair()
        val pubKeyHex = pubKey.toHex()

        val legitPayload = QrPeerPayload(
            nxId = "NX-TARGET-VICTIM",
            displayName = "Victim Name",
            publicKeyHex = pubKeyHex,
            blePeerId = "998877665544",
            expiresAtEpochSec = null
        )

        val encodedUri = QrPayloadCodec.encode(legitPayload, privKey)
        val decodedPayload = QrPayloadCodec.decode(encodedUri)!!

        // Attacker alters victim NX-ID to steal identity
        val tamperedPayload = decodedPayload.copy(nxId = "NX-ATTACKER-ID")

        val result = QrSecurityValidator.validate(tamperedPayload)
        assertEquals(
            "Tampered NX-ID must cause signature verification failure",
            QrValidationResult.SignatureMismatch,
            result
        )
    }

    @Test
    fun testSecurityValidatorEnforces15MinuteAntiStalkingExpiry() {
        val (privKey, pubKey) = generateEd25519KeyPair()
        val pubKeyHex = pubKey.toHex()
        val fixedExpiry = 1700000000L

        val ephemeralPayload = QrPeerPayload(
            nxId = "NX-EPHEMERAL-1",
            displayName = "Temporary Contact",
            publicKeyHex = pubKeyHex,
            blePeerId = "1234567890AB",
            expiresAtEpochSec = fixedExpiry
        )

        val encodedUri = QrPayloadCodec.encode(ephemeralPayload, privKey)
        val decodedPayload = QrPayloadCodec.decode(encodedUri)!!

        // 1. Scan at time past expiration
        val expiredResult = QrSecurityValidator.validate(
            decodedPayload,
            currentEpochSec = fixedExpiry + 1
        )
        assertEquals(
            "Expired QR code must yield QrValidationResult.Expired",
            QrValidationResult.Expired,
            expiredResult
        )

        // 2. Scan at exact expiry or before
        val validResult = QrSecurityValidator.validate(
            decodedPayload,
            currentEpochSec = fixedExpiry - 100
        )
        assertEquals(
            "Unexpired QR code must yield QrValidationResult.Valid",
            QrValidationResult.Valid,
            validResult
        )
    }

    @Test
    fun testSecurityValidatorRejectsMalformedKeyAndSignatureLengths() {
        // Corrupt public key (too short: 30 bytes instead of 32 bytes)
        val shortPubKeyHex = "00".repeat(30)
        val fakeSigHex = "ff".repeat(64)

        val malformedKeyPayload = QrPeerPayload(
            nxId = "NX-BAD-KEY",
            displayName = "Bad Key User",
            publicKeyHex = shortPubKeyHex,
            blePeerId = "112233445566",
            signatureHex = fakeSigHex
        )

        assertEquals(
            "Non-32-byte public key must return MalformedData",
            QrValidationResult.MalformedData,
            QrSecurityValidator.validate(malformedKeyPayload)
        )

        // Corrupt signature (too short: 60 bytes instead of 64 bytes)
        val validPubKeyHex = "aa".repeat(32)
        val shortSigHex = "ff".repeat(60)

        val malformedSigPayload = QrPeerPayload(
            nxId = "NX-BAD-SIG",
            displayName = "Bad Sig User",
            publicKeyHex = validPubKeyHex,
            blePeerId = "112233445566",
            signatureHex = shortSigHex
        )

        assertEquals(
            "Non-64-byte signature must return MalformedData",
            QrValidationResult.MalformedData,
            QrSecurityValidator.validate(malformedSigPayload)
        )
    }

    @Test
    fun testContactEntityVerifiedStatusAndBlePeerMapping() {
        val entity = ContactEntity(
            nxId = "NX-QR-VERIFIED",
            displayName = "Verified Peer",
            publicKeyHex = "aa".repeat(32),
            introMessage = "Verified via QR",
            status = ContactStatus.CONTACT_VERIFIED,
            blePeerId = "MESH-PEER-XYZ"
        )

        assertEquals("Status must be CONTACT_VERIFIED", ContactStatus.CONTACT_VERIFIED, entity.status)
        assertEquals("BLE Peer ID must be preserved", "MESH-PEER-XYZ", entity.blePeerId)
    }
}
