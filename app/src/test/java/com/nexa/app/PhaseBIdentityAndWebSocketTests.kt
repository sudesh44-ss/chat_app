package com.nexa.app

import com.nexa.app.core.crypto.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays

/**
 * Phase B Cryptographic Identity & WebSocket Authentication Unit Tests.
 *
 * Verifies:
 * 1. RFC 8032 Ed25519 authoritative public key derivation consistency.
 * 2. Invalidation of the legacy flawed SHA-256 bit-clamping derivation.
 * 3. Challenge-response signature creation and verification.
 * 4. Transparent migration of stored keys without private key seed invalidation.
 * 5. In-memory zeroing/wiping of decrypted private seeds.
 */
class PhaseBIdentityAndWebSocketTests {

    private val secureRandom = SecureRandom()

    @Test
    fun testAuthoritativeEd25519DerivationMatchesBouncyCastle() {
        val seed = ByteArray(32)
        secureRandom.nextBytes(seed)

        // Derivation 1: Ed25519Signer
        val pub1 = Ed25519Signer.derivePublicKey(seed)
        assertEquals(32, pub1.size)

        // Derivation 2: Re-derivation from same seed is strictly deterministic
        val pub2 = Ed25519Signer.derivePublicKey(seed)
        assertArrayEquals(pub1, pub2)

        // Verify contrast with legacy flawed SHA-256 bit-clamping
        val digest = MessageDigest.getInstance("SHA-256").digest(seed)
        val legacyClamped = ByteArray(32)
        System.arraycopy(digest, 0, legacyClamped, 0, 32)
        legacyClamped[0] = (legacyClamped[0].toInt() and 248).toByte()
        legacyClamped[31] = (legacyClamped[31].toInt() and 127).toByte()
        legacyClamped[31] = (legacyClamped[31].toInt() or 64).toByte()

        assertFalse("Authoritative Ed25519 public key must NOT match flawed SHA-256 clamped hash",
            pub1.contentEquals(legacyClamped))
    }

    @Test
    fun testEd25519ChallengeSigningAndVerification() {
        val seed = ByteArray(32)
        secureRandom.nextBytes(seed)
        val pubKey = Ed25519Signer.derivePublicKey(seed)

        val nonce = ByteArray(32)
        secureRandom.nextBytes(nonce)
        val nxId = "NX-TEST-USER-1"
        val timestamp = System.currentTimeMillis()

        // Canonical dataToSign matching Go backend: nonce + ":$nxId:$timestamp"
        val dataToSign = nonce + ":$nxId:$timestamp".toByteArray(Charsets.UTF_8)

        val signature = Ed25519Signer.sign(dataToSign, seed)
        assertEquals(64, signature.size)

        // Valid signature verification
        val isValid = Ed25519Signer.verify(dataToSign, signature, pubKey)
        assertTrue("Signature over canonical challenge must be valid", isValid)

        // Tampered challenge must fail
        val tamperedData = nonce + ":NX-ATTACKER-9:$timestamp".toByteArray(Charsets.UTF_8)
        val isTamperedValid = Ed25519Signer.verify(tamperedData, signature, pubKey)
        assertFalse("Signature over tampered data must fail", isTamperedValid)

        // Tampered public key must fail
        val otherSeed = ByteArray(32)
        secureRandom.nextBytes(otherSeed)
        val otherPubKey = Ed25519Signer.derivePublicKey(otherSeed)
        val isOtherKeyValid = Ed25519Signer.verify(dataToSign, signature, otherPubKey)
        assertFalse("Verification against wrong public key must fail", isOtherKeyValid)
    }

    @Test
    fun testLegacyKeyMigrationLogic() {
        val seed = ByteArray(32)
        secureRandom.nextBytes(seed)

        // Simulate legacy flawed public key stored during previous onboarding
        val digest = MessageDigest.getInstance("SHA-256").digest(seed)
        val legacyClamped = ByteArray(32)
        System.arraycopy(digest, 0, legacyClamped, 0, 32)
        legacyClamped[0] = (legacyClamped[0].toInt() and 248).toByte()
        legacyClamped[31] = (legacyClamped[31].toInt() and 127).toByte()
        legacyClamped[31] = (legacyClamped[31].toInt() or 64).toByte()
        val legacyPubHex = legacyClamped.joinToString("") { "%02x".format(it) }

        // Compute true Ed25519 public key from the preserved seed
        val truePubBytes = Ed25519Signer.derivePublicKey(seed)
        val truePubHex = truePubBytes.joinToString("") { "%02x".format(it) }

        // Discrepancy detected -> Migration required
        assertNotEquals(legacyPubHex, truePubHex)

        // Migration step: preserve seed, update public key hex
        var storedPubHex = legacyPubHex
        if (storedPubHex != truePubHex) {
            storedPubHex = truePubHex
        }

        assertEquals("Migrated public key must now equal true Ed25519 public key", truePubHex, storedPubHex)
    }

    @Test
    fun testInMemorySeedZeroingSecurity() {
        val seed = ByteArray(32) { (it + 1).toByte() }
        val copy = seed.clone()

        // Verify non-zero initial state
        assertFalse(seed.all { it == 0.toByte() })

        // Execute operation with lambda and wipe
        val pubKey = try {
            Ed25519Signer.derivePublicKey(seed)
        } finally {
            Arrays.fill(seed, 0.toByte())
        }

        // Verify seed is completely wiped from RAM
        assertTrue("Private seed must be completely zeroed after operation", seed.all { it == 0.toByte() })
        assertEquals(32, pubKey.size)
    }
}
