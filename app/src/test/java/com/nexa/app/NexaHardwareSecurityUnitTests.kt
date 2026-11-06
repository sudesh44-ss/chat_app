package com.nexa.app

import com.nexa.app.core.keystore.HardwareSecurityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec

/**
 * Unit Test Suite verifying the EC P-256 Hardware Security architecture:
 * - P-256 keypair generation conforms to standard secp256r1 curve
 * - Public key exports as standard X.509 SubjectPublicKeyInfo DER
 * - SHA256withECDSA challenge signing and interoperability verification
 * - Signature malleability and tamper resistance
 * - HardwareSecurityLevel enum classification
 */
class NexaHardwareSecurityUnitTests {

    @Test
    fun testEcP256KeyGenerationAndDerExport() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()

        assertNotNull("Generated keypair must not be null", keyPair)
        assertNotNull("Private key must not be null", keyPair.private)
        assertNotNull("Public key must not be null", keyPair.public)

        // Verify standard X.509 DER export
        val derBytes = keyPair.public.encoded
        assertNotNull("Public key DER bytes must not be null", derBytes)
        assertTrue("X.509 DER public key for P-256 should be 91 bytes", derBytes.size == 91)

        // Verify that the DER bytes can be re-imported by KeyFactory
        val keyFactory = KeyFactory.getInstance("EC")
        val parsedPubKey = keyFactory.generatePublic(X509EncodedKeySpec(derBytes))
        assertEquals("Parsed public key algorithm must be EC", "EC", parsedPubKey.algorithm)
    }

    @Test
    fun testSha256WithEcdsaChallengeSigning() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()

        val challengeNonce = "test_challenge_nonce_32_bytes_12".toByteArray(Charsets.UTF_8)

        // Sign challenge with private key
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(challengeNonce)
        val signature = signer.sign()

        assertNotNull("ECDSA signature must not be null", signature)
        // ASN.1 DER ECDSA signature is typically 70-72 bytes
        assertTrue("ECDSA signature length must be valid ASN.1 DER (68-73 bytes)", signature.size in 68..73)

        // Verify with public key
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(keyPair.public)
        verifier.update(challengeNonce)
        assertTrue("Signature verification must pass for matching challenge", verifier.verify(signature))
    }

    @Test
    fun testTamperedChallengeFailsSignatureVerification() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()

        val originalChallenge = "challenge_original_payload_123".toByteArray(Charsets.UTF_8)
        val tamperedChallenge = "challenge_tampered_payload_999".toByteArray(Charsets.UTF_8)

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(originalChallenge)
        val signature = signer.sign()

        // Verifying against tampered payload must fail
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(keyPair.public)
        verifier.update(tamperedChallenge)
        assertFalse("Signature verification must fail on tampered challenge", verifier.verify(signature))
    }

    @Test
    fun testHardwareSecurityLevelEnumValues() {
        val levels = HardwareSecurityLevel.values()
        assertTrue(levels.contains(HardwareSecurityLevel.STRONGBOX))
        assertTrue(levels.contains(HardwareSecurityLevel.TEE))
        assertTrue(levels.contains(HardwareSecurityLevel.SOFTWARE))
        assertTrue(levels.contains(HardwareSecurityLevel.UNKNOWN))
    }
}
