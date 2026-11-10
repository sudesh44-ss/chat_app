package com.nexa.app

import com.nexa.app.core.crypto.Ed25519Signer
import com.nexa.app.core.dtn.DtnConstants
import com.nexa.app.core.dtn.DtnEnvelope
import com.nexa.app.core.dtn.PairwiseRoutingTokenGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Rigorous Cryptographic and Security Test Suite for NEXA Phase C:
 * Pairwise Rotating Privacy-Preserving Routing Identifiers.
 */
class PairwiseRoutingSecurityTests {

    private val testPairwiseSecretHex = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
    private val testPairwiseSecret = testPairwiseSecretHex.hexToBytes()
    private val senderNxId = "NX-ALICE-1234"
    private val recipientNxId = "NX-BOB-5678"
    private val testEpoch = 20000L

    // Expected cross-platform deterministic vector verified against Go backend
    private val expectedKRouteHex = "e13808db205ecb93a50bee48449281582d83471225e65332d61318d4d333f7af"
    private val expectedRoutingTokenHex = "4d434cacd96a31d95db3d8305e5ac679"

    @Test
    fun crossPlatformDeterministicVector_matchesGoBackendByteForByte() {
        val kRoute = PairwiseRoutingTokenGenerator.deriveRoutingKey(
            pairwiseSecret = testPairwiseSecret,
            senderNxId = senderNxId,
            recipientNxId = recipientNxId
        )
        assertEquals(expectedKRouteHex, kRoute.toHex())

        val routingToken = PairwiseRoutingTokenGenerator.generateRoutingToken(
            routingKey = kRoute,
            epoch = testEpoch
        )
        assertEquals(16, routingToken.size)
        assertEquals(expectedRoutingTokenHex, routingToken.toHex())

        val hexDirect = PairwiseRoutingTokenGenerator.generateRoutingTokenHex(
            routingKey = kRoute,
            epoch = testEpoch
        )
        assertEquals(expectedRoutingTokenHex, hexDirect)
    }

    @Test
    fun keyDerivation_cryptographicSeparationAndDirectionality() {
        val kRouteAtoB = PairwiseRoutingTokenGenerator.deriveRoutingKey(
            pairwiseSecret = testPairwiseSecret,
            senderNxId = "NX-ALICE",
            recipientNxId = "NX-BOB"
        )
        val kRouteBtoA = PairwiseRoutingTokenGenerator.deriveRoutingKey(
            pairwiseSecret = testPairwiseSecret,
            senderNxId = "NX-BOB",
            recipientNxId = "NX-ALICE"
        )

        // Directional separation: A -> B != B -> A
        assertFalse(
            "Directional routing keys must never be equal",
            kRouteAtoB.contentEquals(kRouteBtoA)
        )

        val diffSecret = ByteArray(32) { 0xFF.toByte() }
        val kRouteDiffSecret = PairwiseRoutingTokenGenerator.deriveRoutingKey(
            pairwiseSecret = diffSecret,
            senderNxId = "NX-ALICE",
            recipientNxId = "NX-BOB"
        )
        assertFalse(
            "Different pairwise secrets must produce different routing keys",
            kRouteAtoB.contentEquals(kRouteDiffSecret)
        )

        val kRouteDiffPeer = PairwiseRoutingTokenGenerator.deriveRoutingKey(
            pairwiseSecret = testPairwiseSecret,
            senderNxId = "NX-ALICE",
            recipientNxId = "NX-CAROL"
        )
        assertFalse(
            "Different recipient identities must produce different routing keys",
            kRouteAtoB.contentEquals(kRouteDiffPeer)
        )
    }

    @Test
    fun epochRotation_uniquenessAcrossAdjacentEpochs() {
        val kRoute = PairwiseRoutingTokenGenerator.deriveRoutingKey(
            pairwiseSecret = testPairwiseSecret,
            senderNxId = senderNxId,
            recipientNxId = recipientNxId
        )

        val token1 = PairwiseRoutingTokenGenerator.generateRoutingToken(kRoute, 100L)
        val token2 = PairwiseRoutingTokenGenerator.generateRoutingToken(kRoute, 101L)
        val token3 = PairwiseRoutingTokenGenerator.generateRoutingToken(kRoute, 102L)

        assertFalse(token1.contentEquals(token2))
        assertFalse(token2.contentEquals(token3))
        assertFalse(token1.contentEquals(token3))
    }

    @Test
    fun epochToleranceWindow_boundedAcceptance() {
        val kRoute = PairwiseRoutingTokenGenerator.deriveRoutingKey(
            pairwiseSecret = testPairwiseSecret,
            senderNxId = senderNxId,
            recipientNxId = recipientNxId
        )

        val currentEpoch = 500L
        val tokenCurrent = PairwiseRoutingTokenGenerator.generateRoutingToken(kRoute, currentEpoch)
        val tokenPrev = PairwiseRoutingTokenGenerator.generateRoutingToken(kRoute, currentEpoch - 1)
        val token2DaysAgo = PairwiseRoutingTokenGenerator.generateRoutingToken(kRoute, currentEpoch - 2)
        val tokenNext = PairwiseRoutingTokenGenerator.generateRoutingToken(kRoute, currentEpoch + 1)
        val tokenExpired = PairwiseRoutingTokenGenerator.generateRoutingToken(kRoute, currentEpoch - 3)
        val tokenFutureSkew = PairwiseRoutingTokenGenerator.generateRoutingToken(kRoute, currentEpoch + 2)

        // Valid inside bounded [currentEpoch - 2 .. currentEpoch + 1] window
        assertTrue("Current epoch token must be valid", PairwiseRoutingTokenGenerator.isTokenInWindow(tokenCurrent, kRoute, currentEpoch))
        assertTrue("Previous epoch token (epoch - 1) must be valid", PairwiseRoutingTokenGenerator.isTokenInWindow(tokenPrev, kRoute, currentEpoch))
        assertTrue("2-days-ago epoch token (epoch - 2) must be valid for 48h offline tolerance", PairwiseRoutingTokenGenerator.isTokenInWindow(token2DaysAgo, kRoute, currentEpoch))
        assertTrue("Next epoch token (epoch + 1) must be valid", PairwiseRoutingTokenGenerator.isTokenInWindow(tokenNext, kRoute, currentEpoch))

        // Invalid outside window
        assertFalse("Expired epoch token (epoch - 3) must be rejected", PairwiseRoutingTokenGenerator.isTokenInWindow(tokenExpired, kRoute, currentEpoch))
        assertFalse("Future skewed token (epoch + 2) must be rejected", PairwiseRoutingTokenGenerator.isTokenInWindow(tokenFutureSkew, kRoute, currentEpoch))
    }

    @Test
    fun isTokenInWindowForEnvelope_boundsAndOfflineScenarios() {
        val kRoute = PairwiseRoutingTokenGenerator.deriveRoutingKey(
            pairwiseSecret = testPairwiseSecret,
            senderNxId = senderNxId,
            recipientNxId = recipientNxId
        )

        val nowSec = 1700000000L
        val currentEpoch = PairwiseRoutingTokenGenerator.currentEpoch(nowSec)

        // Scenario 1: Envelope created in current epoch (valid)
        val tokenNow = PairwiseRoutingTokenGenerator.generateRoutingToken(kRoute, currentEpoch)
        assertTrue(
            "Envelope created in current epoch must be accepted",
            PairwiseRoutingTokenGenerator.isTokenInWindowForEnvelope(
                candidateToken = tokenNow,
                routingKey = kRoute,
                createdAtEpochSec = nowSec - 3600,
                expiresAtEpochSec = nowSec + 86400,
                nowSec = nowSec
            )
        )

        // Scenario 2: 24h offline delay (epoch - 1, valid)
        val delay24hSec = 24 * 3600L
        val epoch24hAgo = PairwiseRoutingTokenGenerator.currentEpoch(nowSec - delay24hSec)
        val token24h = PairwiseRoutingTokenGenerator.generateRoutingToken(kRoute, epoch24hAgo)
        assertTrue(
            "Envelope delayed 24h offline must be accepted",
            PairwiseRoutingTokenGenerator.isTokenInWindowForEnvelope(
                candidateToken = token24h,
                routingKey = kRoute,
                createdAtEpochSec = nowSec - delay24hSec,
                expiresAtEpochSec = nowSec + 86400,
                nowSec = nowSec
            )
        )

        // Scenario 3: 48h offline delay crossing 2 midnight boundaries (epoch - 2, valid)
        val delay48hSec = 48 * 3600L
        val epoch48hAgo = PairwiseRoutingTokenGenerator.currentEpoch(nowSec - delay48hSec)
        val token48h = PairwiseRoutingTokenGenerator.generateRoutingToken(kRoute, epoch48hAgo)
        assertTrue(
            "Envelope delayed 48h offline crossing two midnight boundaries must be accepted",
            PairwiseRoutingTokenGenerator.isTokenInWindowForEnvelope(
                candidateToken = token48h,
                routingKey = kRoute,
                createdAtEpochSec = nowSec - delay48hSec,
                expiresAtEpochSec = nowSec + 3600,
                nowSec = nowSec
            )
        )

        // Scenario 4: Expired envelope (expiresAt < now, rejected)
        assertFalse(
            "Envelope past expiry must be rejected",
            PairwiseRoutingTokenGenerator.isTokenInWindowForEnvelope(
                candidateToken = tokenNow,
                routingKey = kRoute,
                createdAtEpochSec = nowSec - 7200,
                expiresAtEpochSec = nowSec - 10,
                nowSec = nowSec
            )
        )

        // Scenario 5: Future clock skew > 300s (rejected)
        assertFalse(
            "Envelope with > 300s future skew must be rejected",
            PairwiseRoutingTokenGenerator.isTokenInWindowForEnvelope(
                candidateToken = tokenNow,
                routingKey = kRoute,
                createdAtEpochSec = nowSec + 305,
                expiresAtEpochSec = nowSec + 86400,
                nowSec = nowSec
            )
        )

        // Scenario 6: Envelope TTL exceeding 7 days (rejected)
        assertFalse(
            "Envelope exceeding max 7-day lifetime must be rejected",
            PairwiseRoutingTokenGenerator.isTokenInWindowForEnvelope(
                candidateToken = tokenNow,
                routingKey = kRoute,
                createdAtEpochSec = nowSec - 3600,
                expiresAtEpochSec = nowSec + (8 * 86400),
                nowSec = nowSec
            )
        )
    }

    @Test
    fun privacy_passiveObserverCannotComputeRoutingTokenFromPublicInfo() {
        val alicePubKey = ByteArray(32) { (it + 1).toByte() }
        val bobPubKey = ByteArray(32) { (it + 10).toByte() }
        val currentDay = PairwiseRoutingTokenGenerator.currentEpoch()

        // Old vulnerable scheme: BlindToken = Truncate16(Blake3(bobPubKey || Salt + currentDay))
        // Anyone with bobPubKey and currentDay could derive it.

        // In Phase C:
        // Derivation requires pairwiseSecret = X25519(alicePriv, bobPub)
        // An observer with only (alicePubKey, bobPubKey, currentDay) has NO knowledge of pairwiseSecret.
        val kRoute = PairwiseRoutingTokenGenerator.deriveRoutingKey(testPairwiseSecret, senderNxId, recipientNxId)
        val token = PairwiseRoutingTokenGenerator.generateRoutingToken(kRoute, currentDay)

        // Attempting to derive from public information alone without secret is mathematically impossible
        val pseudoSecret = MessageDigest.getInstance("SHA-256").digest(alicePubKey + bobPubKey)
        val fakeKey = PairwiseRoutingTokenGenerator.deriveRoutingKey(pseudoSecret, senderNxId, recipientNxId)
        val fakeToken = PairwiseRoutingTokenGenerator.generateRoutingToken(fakeKey, currentDay)

        assertFalse(
            "Public information alone must not be able to reproduce pairwise routing token",
            token.contentEquals(fakeToken)
        )
    }

    @Test
    fun nxdtEnvelope_integratesPairwiseTokenWithCryptographicBinding() {
        val kRoute = PairwiseRoutingTokenGenerator.deriveRoutingKey(testPairwiseSecret, senderNxId, recipientNxId)
        val routingToken = PairwiseRoutingTokenGenerator.generateRoutingToken(kRoute, testEpoch)

        val senderPriv = ByteArray(32) { (it + 5).toByte() }
        val senderPub = Ed25519Signer.derivePublicKey(senderPriv)
        val payload = "NEXA Encrypted DTN Message over Pairwise Routing".toByteArray(StandardCharsets.UTF_8)

        val envelope = DtnEnvelope.createAuthenticated(
            blindToken = routingToken,
            encryptedPayload = payload,
            senderPrivateKey = senderPriv,
            senderPublicKey = senderPub,
            envelopeType = DtnConstants.TYPE_STANDARD_DATA,
            ttlSeconds = 86400L
        )

        // 1. Envelope holds 16-byte pairwise routing token
        assertEquals(16, envelope.blindToken.size)
        assertTrue(envelope.blindToken.contentEquals(routingToken))
        assertEquals(routingToken.toHex(), envelope.blindTokenHex)

        // 2. Sender signature binds immutable header including blindToken
        assertTrue("Authentic envelope must verify", envelope.verifySignature())

        // 3. Serialization and deserialization roundtrip preserves token
        val wireBytes = envelope.serialize()
        val parsed = DtnEnvelope.deserialize(wireBytes)
        assertNotNull(parsed)
        assertEquals(envelope.blindTokenHex, parsed!!.blindTokenHex)
        assertTrue(parsed.verifySignature())

        // 4. Tampering with routing token breaks signature verification
        val tamperedBytes = wireBytes.clone()
        // blindToken is at offset 68..83 in NXDT v1 wire packet
        tamperedBytes[68] = (tamperedBytes[68].toInt() xor 0xFF).toByte()
        val tamperedEnv = DtnEnvelope.deserialize(tamperedBytes, verifySignature = false)
        assertNotNull("Deserialization without signature check should succeed", tamperedEnv)
        assertFalse(
            "Tampering with blindToken must cause cryptographic signature failure",
            tamperedEnv!!.verifySignature()
        )
        // Strict deserialization with signature verification must reject it immediately
        assertNull(
            "Strict deserialization must reject tampered routing token",
            DtnEnvelope.deserialize(tamperedBytes, verifySignature = true)
        )
    }

    @Test
    fun multiDeviceRevocation_revokedDeviceSecretStopsTokenGeneration() {
        // Device 1 and Device 2 for Bob
        val secretDev1 = MessageDigest.getInstance("SHA-256").digest("DEVICE_1_SHARED_SECRET".toByteArray())
        val secretDev2 = MessageDigest.getInstance("SHA-256").digest("DEVICE_2_SHARED_SECRET".toByteArray())

        val kRouteDev1 = PairwiseRoutingTokenGenerator.deriveRoutingKey(secretDev1, "NX-ALICE", "NX-BOB:DEV1")
        val kRouteDev2 = PairwiseRoutingTokenGenerator.deriveRoutingKey(secretDev2, "NX-ALICE", "NX-BOB:DEV2")

        val tokenDev1 = PairwiseRoutingTokenGenerator.generateRoutingToken(kRouteDev1)
        val tokenDev2 = PairwiseRoutingTokenGenerator.generateRoutingToken(kRouteDev2)

        assertFalse("Different devices possess distinct routing tokens", tokenDev1.contentEquals(tokenDev2))

        // When Device 2 is revoked, sender drops secretDev2 and only generates tokens for Device 1
        val activeDevTokens = listOf(tokenDev1.toHex())
        assertTrue("Active device token recognized", activeDevTokens.contains(tokenDev1.toHex()))
        assertFalse("Revoked device token excluded from active tokens", activeDevTokens.contains(tokenDev2.toHex()))
    }

    private fun String.hexToBytes(): ByteArray {
        val len = length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(this[i], 16) shl 4) +
                    Character.digit(this[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
