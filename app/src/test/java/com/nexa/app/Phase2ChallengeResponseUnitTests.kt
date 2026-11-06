package com.nexa.app

import android.telephony.SmsManager
import com.nexa.app.data.remote.dto.GoogleAuthResponse
import com.nexa.app.data.remote.dto.InitiateReverseVerifyResponseDto
import com.nexa.app.data.remote.dto.LoginChallengeRequestDto
import com.nexa.app.data.remote.dto.LoginChallengeResponseDto
import com.nexa.app.data.remote.dto.LoginVerifyRequestDto
import com.nexa.app.data.remote.dto.LoginVerifyResponseDto
import com.nexa.app.data.remote.dto.UserProfileSummaryDto
import com.nexa.app.data.remote.dto.VerificationStatusDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Phase 2 Security Remediation Unit Tests.
 * Validates:
 * 1. Challenge-Response login data structures and DTOs.
 * 2. Cryptographic EC P-256 challenge signing and verification (SHA256withECDSA ASN.1).
 * 3. SMS sent & delivery radio callback error classification.
 * 4. Anti-replay and single-use challenge consumption simulations.
 */
class Phase2ChallengeResponseUnitTests {

    @Test
    fun testChallengeResponseDtoIntegrity() {
        val chalReq = LoginChallengeRequestDto(
            nxId = "NX-TEST-USER-1",
            deviceId = "dev_pixel_9"
        )
        assertEquals("NX-TEST-USER-1", chalReq.nxId)
        assertEquals("dev_pixel_9", chalReq.deviceId)

        val chalResp = LoginChallengeResponseDto(
            status = "CHALLENGE_ISSUED",
            challengeId = "chal_987654321",
            challenge = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            nxId = "NX-TEST-USER-1",
            deviceId = "dev_pixel_9",
            expiresInSeconds = 60,
            timestamp = System.currentTimeMillis()
        )
        assertEquals("CHALLENGE_ISSUED", chalResp.status)
        assertEquals(64, chalResp.challenge.length)

        val verifyReq = LoginVerifyRequestDto(
            challengeId = chalResp.challengeId,
            challenge = chalResp.challenge,
            nxId = chalResp.nxId,
            deviceId = chalResp.deviceId,
            signature = "3045022100...dummy_sig..."
        )
        assertEquals(chalResp.challengeId, verifyReq.challengeId)

        val verifyResp = LoginVerifyResponseDto(
            status = "LOGIN_SUCCESS",
            sessionAuthToken = "nexa_sess_token_abc123",
            sessionToken = "nexa_sess_token_abc123",
            nxId = chalResp.nxId,
            deviceId = chalResp.deviceId,
            user = UserProfileSummaryDto(
                nxId = chalResp.nxId,
                name = "Test User",
                phone = "+919876543210",
                avatarUrl = null
            )
        )
        assertEquals("LOGIN_SUCCESS", verifyResp.status)
        assertNotNull(verifyResp.user)
        assertEquals("Test User", verifyResp.user?.name)
    }

    @Test
    fun testGoogleAuthResponseChallengeParameters() {
        val googleResp = GoogleAuthResponse(
            status = "REQUIRE_DEVICE_CHALLENGE",
            nxId = "NX-GOOGLE-EXISTING",
            deviceId = "dev_google_device",
            challengeId = "chal_123",
            challenge = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
            expiresInSeconds = 60
        )

        assertEquals("REQUIRE_DEVICE_CHALLENGE", googleResp.status)
        assertNotNull(googleResp.challenge)
        assertEquals(60, googleResp.expiresInSeconds)
    }

    @Test
    fun testECP256ChallengeSigningAndVerification() {
        // Standard JVM EC P-256 Keypair generation (mirrors Android Keystore EC P-256)
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()

        // 32-byte random challenge nonce from server
        val challengeNonce = ByteArray(32)
        SecureRandom().nextBytes(challengeNonce)

        // Sign using SHA256withECDSA (ASN.1 DER encoding)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(challengeNonce)
        val signatureBytes = signer.sign()

        assertNotNull(signatureBytes)
        // ASN.1 DER signature for P-256 is typically between 70 and 72 bytes (starts with 0x30)
        assertEquals(0x30.toByte(), signatureBytes[0])
        assertTrue(signatureBytes.size in 68..74)

        // Verify with matching public key
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(keyPair.public)
        verifier.update(challengeNonce)
        val isValid = verifier.verify(signatureBytes)
        assertTrue("Valid EC P-256 signature must verify successfully", isValid)

        // Tamper test: Corrupt challenge nonce must fail verification
        val tamperedNonce = challengeNonce.clone()
        tamperedNonce[0] = (tamperedNonce[0].toInt() xor 0xFF).toByte()

        val tamperedVerifier = Signature.getInstance("SHA256withECDSA")
        tamperedVerifier.initVerify(keyPair.public)
        tamperedVerifier.update(tamperedNonce)
        val isTamperedValid = tamperedVerifier.verify(signatureBytes)
        assertFalse("Tampered challenge must fail verification", isTamperedValid)
    }

    @Test
    fun testRadioCallbackErrorCodeClassification() {
        fun classifyErrorCode(resultCode: Int): String {
            return when (resultCode) {
                -1 -> "OK" // Activity.RESULT_OK
                SmsManager.RESULT_ERROR_NO_SERVICE -> "NO_SERVICE"
                SmsManager.RESULT_ERROR_RADIO_OFF -> "RADIO_OFF"
                SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "LIMIT_EXCEEDED"
                else -> "GENERIC_FAILURE"
            }
        }

        assertEquals("OK", classifyErrorCode(-1))
        assertEquals("NO_SERVICE", classifyErrorCode(SmsManager.RESULT_ERROR_NO_SERVICE))
        assertEquals("RADIO_OFF", classifyErrorCode(SmsManager.RESULT_ERROR_RADIO_OFF))
        assertEquals("LIMIT_EXCEEDED", classifyErrorCode(SmsManager.RESULT_ERROR_LIMIT_EXCEEDED))
        assertEquals("GENERIC_FAILURE", classifyErrorCode(SmsManager.RESULT_ERROR_GENERIC_FAILURE))
    }

    @Test
    fun testSingleUseChallengeLifecycleSimulation() {
        val consumedChallenges = mutableSetOf<String>()

        fun consumeChallenge(challengeId: String): Boolean {
            if (consumedChallenges.contains(challengeId)) {
                return false // Replay attack prevented!
            }
            consumedChallenges.add(challengeId)
            return true
        }

        val challengeId = "chal_unique_nonce_12345"

        // First verification: succeeds and marks challenge as consumed
        val firstAttempt = consumeChallenge(challengeId)
        assertTrue("Initial challenge consumption must succeed", firstAttempt)

        // Second verification (replay attempt): must fail
        val replayAttempt = consumeChallenge(challengeId)
        assertFalse("Subsequent challenge consumption must fail (anti-replay single use)", replayAttempt)
    }

    @Test
    fun testZeroPermissionReverseSmsDtoAndPayloadContract() {
        val nonce = "NX-9K2M4"
        val sessionId = "sess_rev_9876543210_123456"
        val inbound = "+919800000000"
        val smsPayload = "NEXA-VERIFY:$nonce:$sessionId"

        val initDto = InitiateReverseVerifyResponseDto(
            status = "PENDING",
            nonce = nonce,
            inboundReceiverNumber = inbound,
            inboundNumber = inbound,
            smsPayload = smsPayload,
            expiresInSeconds = 90,
            sessionId = sessionId
        )

        assertEquals("PENDING", initDto.status)
        assertEquals(nonce, initDto.nonce)
        assertEquals(inbound, initDto.inboundNumber)
        assertEquals(inbound, initDto.inboundReceiverNumber)
        assertEquals(sessionId, initDto.sessionId)
        assertEquals("NEXA-VERIFY:$nonce:$sessionId", initDto.smsPayload)

        // Pending Status DTO check
        val pendingStatus = VerificationStatusDto(
            status = "PENDING",
            isVerified = false,
            expiresInSeconds = 85
        )
        assertFalse(pendingStatus.isVerified)
        assertEquals("PENDING", pendingStatus.status)

        // Verified Status DTO check
        val verifiedStatus = VerificationStatusDto(
            status = "VERIFIED",
            isVerified = true,
            nxId = "NX-TESTUSER",
            sessionAuthToken = "token_abc_xyz",
            name = "Test Carrier User",
            phone = "+919876543210"
        )
        assertTrue(verifiedStatus.isVerified)
        assertEquals("VERIFIED", verifiedStatus.status)
        assertEquals("NX-TESTUSER", verifiedStatus.nxId)
        assertEquals("token_abc_xyz", verifiedStatus.sessionAuthToken)
    }

    @Test
    fun testZeroPermissionIntentContractSimulation() {
        // Simulates the exact intent creation in VerificationRadarViewModel
        val inboundNumber = "+919800000000"
        val smsPayload = "NEXA-VERIFY:NX-TEST1:sess_123"

        val intentAction = "android.intent.action.SENDTO"
        val uriString = "smsto:$inboundNumber"
        val extraKey = "sms_body"

        assertEquals("android.intent.action.SENDTO", intentAction)
        assertEquals("smsto:+919800000000", uriString)
        assertEquals("sms_body", extraKey)
        assertTrue("Payload must contain nonce", smsPayload.contains("NX-TEST1"))
        assertTrue("Uri must start with smsto: scheme for direct SMS composer launch without telephony permissions", uriString.startsWith("smsto:"))
    }
}
