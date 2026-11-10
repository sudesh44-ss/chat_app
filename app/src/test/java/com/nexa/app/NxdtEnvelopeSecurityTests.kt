package com.nexa.app

import com.nexa.app.core.crypto.Ed25519Signer
import com.nexa.app.core.dtn.DtnConstants
import com.nexa.app.core.dtn.DtnEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

class NxdtEnvelopeSecurityTests {

    private fun generateTestKeySeed(): ByteArray {
        val seed = ByteArray(32)
        SecureRandom().nextBytes(seed)
        return seed
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            result.append(hexChars[i ushr 4])
            result.append(hexChars[i and 0x0F])
        }
        return result.toString()
    }

    // ==========================================
    // 1. SERIALIZATION & WIRE FORMAT TESTS
    // ==========================================

    @Test
    fun nxdtEnvelope_validRoundTrip_preservesAllFieldsAndSignature() {
        val seed = generateTestKeySeed()
        val pubKey = Ed25519Signer.derivePublicKey(seed)
        val blindToken = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val payload = "Autonomous Multi-Hop E2EE Data Mule Packet".toByteArray(StandardCharsets.UTF_8)

        val envelope = DtnEnvelope.createAuthenticated(
            blindToken = blindToken,
            encryptedPayload = payload,
            senderPrivateKey = seed,
            senderPublicKey = pubKey,
            envelopeType = DtnConstants.TYPE_STANDARD_DATA,
            hopLimit = 7,
            ttlSeconds = 3600
        )

        val serialized = envelope.serialize()
        assertEquals(DtnConstants.NXDT_HEADER_SIZE + payload.size, serialized.size)

        // Verify magic bytes "NXDT"
        assertEquals('N'.code.toByte(), serialized[0])
        assertEquals('X'.code.toByte(), serialized[1])
        assertEquals('D'.code.toByte(), serialized[2])
        assertEquals('T'.code.toByte(), serialized[3])

        // Verify protocol version = 1
        assertEquals(DtnConstants.PROTOCOL_VERSION_1, serialized[4])

        // Deserialization with strict signature check
        val parsed = DtnEnvelope.deserialize(serialized, verifySignature = true)
        assertNotNull(parsed)
        assertEquals(DtnConstants.PROTOCOL_VERSION_1, parsed!!.protocolVersion)
        assertEquals(DtnConstants.TYPE_STANDARD_DATA, parsed.envelopeType)
        assertEquals(DtnConstants.SUITE_ED25519_SHA256, parsed.algorithmSuite)
        assertEquals(0.toByte(), parsed.hopCount)
        assertEquals(7.toByte(), parsed.hopLimit)
        assertTrue(envelope.envelopeId.contentEquals(parsed.envelopeId))
        assertTrue(pubKey.contentEquals(parsed.senderPublicKey!!))
        assertTrue(blindToken.contentEquals(parsed.blindToken))
        assertTrue(payload.contentEquals(parsed.encryptedPayload))
        assertTrue(parsed.verifySignature())
        assertTrue(parsed.isAuthenticated)
    }

    @Test
    fun nxdtEnvelope_emptyPayload_succeeds() {
        val seed = generateTestKeySeed()
        val blindToken = ByteArray(16)
        val envelope = DtnEnvelope.createAuthenticated(
            blindToken = blindToken,
            encryptedPayload = ByteArray(0),
            senderPrivateKey = seed,
            envelopeType = DtnConstants.TYPE_ACK_PACKET
        )

        val serialized = envelope.serialize()
        assertEquals(DtnConstants.NXDT_HEADER_SIZE, serialized.size)

        val parsed = DtnEnvelope.deserialize(serialized)
        assertNotNull(parsed)
        assertEquals(0, parsed!!.encryptedPayload.size)
        assertTrue(parsed.verifySignature())
    }

    @Test
    fun nxdtEnvelope_maxPermittedPayload_succeedsAndRejectsOversized() {
        val seed = generateTestKeySeed()
        val blindToken = ByteArray(16)
        val maxPayload = ByteArray(DtnConstants.MAX_PAYLOAD_SIZE)

        val envelope = DtnEnvelope.createAuthenticated(
            blindToken = blindToken,
            encryptedPayload = maxPayload,
            senderPrivateKey = seed
        )

        val serialized = envelope.serialize()
        assertEquals(DtnConstants.MAX_PACKET_SIZE, serialized.size)

        val parsed = DtnEnvelope.deserialize(serialized)
        assertNotNull(parsed)
        assertEquals(DtnConstants.MAX_PAYLOAD_SIZE, parsed!!.encryptedPayload.size)

        // Oversized packet (> 64 KB) must be rejected
        val oversizedPacket = ByteArray(DtnConstants.MAX_PACKET_SIZE + 1)
        System.arraycopy(serialized, 0, oversizedPacket, 0, DtnConstants.NXDT_HEADER_SIZE)
        assertNull(DtnEnvelope.deserialize(oversizedPacket))
    }

    @Test
    fun nxdtEnvelope_truncatedOrMalformedHeaders_returnsNull() {
        // Less than 200 bytes with NXDT magic
        val truncated = ByteArray(150)
        truncated[0] = 'N'.code.toByte()
        truncated[1] = 'X'.code.toByte()
        truncated[2] = 'D'.code.toByte()
        truncated[3] = 'T'.code.toByte()
        assertNull(DtnEnvelope.deserialize(truncated))

        // Ciphertext length claims 50 bytes, but buffer only has 10
        val seed = generateTestKeySeed()
        val blindToken = ByteArray(16)
        val env = DtnEnvelope.createAuthenticated(blindToken, ByteArray(10), seed)
        val serialized = env.serialize()
        // Corrupt length field at bytes 100..103 to claim 50 bytes
        serialized[100] = 0x00
        serialized[101] = 0x00
        serialized[102] = 0x00
        serialized[103] = 0x32 // 50 in decimal
        assertNull(DtnEnvelope.deserialize(serialized))
    }

    @Test
    fun nxdtEnvelope_invalidMagicOrVersion_rejectedSafely() {
        val seed = generateTestKeySeed()
        val blindToken = ByteArray(16)
        val env = DtnEnvelope.createAuthenticated(blindToken, "payload".toByteArray(), seed)
        val serialized = env.serialize()

        // Modify version (offset 4) to 99
        val corruptVersion = serialized.clone()
        corruptVersion[4] = 99
        assertNull(DtnEnvelope.deserialize(corruptVersion))

        // Modify suite (offset 6) to 42
        val corruptSuite = serialized.clone()
        corruptSuite[6] = 42
        assertNull(DtnEnvelope.deserialize(corruptSuite))
    }

    // ==========================================
    // 2. CRYPTOGRAPHIC AUTHENTICATION & TAMPERING
    // ==========================================

    @Test
    fun nxdtEnvelope_tamperedCiphertext_failsHashBinding() {
        val seed = generateTestKeySeed()
        val blindToken = ByteArray(16)
        val payload = "Original untampered payload".toByteArray(StandardCharsets.UTF_8)
        val env = DtnEnvelope.createAuthenticated(blindToken, payload, seed)
        val serialized = env.serialize()

        // Flip a single bit in the payload (offset 200+)
        val tampered = serialized.clone()
        tampered[205] = (tampered[205].toInt() xor 0x01).toByte()

        // Deserialization must return null because payload SHA-256 does not match header
        assertNull(DtnEnvelope.deserialize(tampered))
    }

    @Test
    fun nxdtEnvelope_tamperedImmutableHeader_failsSignatureVerification() {
        val seed = generateTestKeySeed()
        val blindToken = ByteArray(16)
        val payload = "Signed message".toByteArray(StandardCharsets.UTF_8)
        val env = DtnEnvelope.createAuthenticated(blindToken, payload, seed)
        val serialized = env.serialize()

        // 1. Tamper Message UUID (offset 20..35)
        val tamperedUuid = serialized.clone()
        tamperedUuid[25] = (tamperedUuid[25].toInt() xor 0xFF).toByte()
        // If parsed without verifying signature, verifySignature() must return false
        val parsedUuid = DtnEnvelope.deserialize(tamperedUuid, verifySignature = false)
        assertNotNull(parsedUuid)
        assertFalse(parsedUuid!!.verifySignature())
        // Strict deserialize must reject
        assertNull(DtnEnvelope.deserialize(tamperedUuid, verifySignature = true))

        // 2. Tamper Sender Public Key (offset 36..67)
        val tamperedPubkey = serialized.clone()
        tamperedPubkey[40] = (tamperedPubkey[40].toInt() xor 0xFF).toByte()
        val parsedPubkey = DtnEnvelope.deserialize(tamperedPubkey, verifySignature = false)
        assertNotNull(parsedPubkey)
        assertFalse(parsedPubkey!!.verifySignature())
        assertNull(DtnEnvelope.deserialize(tamperedPubkey, verifySignature = true))

        // 3. Tamper Expiry Timestamp (offset 92..99)
        val tamperedExpiry = serialized.clone()
        tamperedExpiry[95] = (tamperedExpiry[95].toInt() xor 0xFF).toByte()
        val parsedExpiry = DtnEnvelope.deserialize(tamperedExpiry, verifySignature = false)
        assertNotNull(parsedExpiry)
        assertFalse(parsedExpiry!!.verifySignature())
        assertNull(DtnEnvelope.deserialize(tamperedExpiry, verifySignature = true))

        // 4. Tamper Creation Timestamp (offset 84..91)
        val tamperedCreated = serialized.clone()
        tamperedCreated[91] = (tamperedCreated[91].toInt() xor 0x01).toByte()
        val parsedCreated = DtnEnvelope.deserialize(tamperedCreated, verifySignature = false)
        assertNotNull(parsedCreated)
        assertFalse(parsedCreated!!.verifySignature())
        assertNull(DtnEnvelope.deserialize(tamperedCreated, verifySignature = true))
    }

    @Test
    fun nxdtEnvelope_replacedSignature_failsVerification() {
        val seed1 = generateTestKeySeed()
        val seed2 = generateTestKeySeed()
        val blindToken = ByteArray(16)
        val payload = "Hello".toByteArray(StandardCharsets.UTF_8)

        val env1 = DtnEnvelope.createAuthenticated(blindToken, payload, seed1)
        val env2 = DtnEnvelope.createAuthenticated(blindToken, payload, seed2)

        // Substitute signature from env2 into env1
        val tampered = env1.copy(signature = env2.signature)
        assertFalse(tampered.verifySignature())
    }

    // ==========================================
    // 3. RELAY MUTABLE METADATA & HOP DECREMENT
    // ==========================================

    @Test
    fun nxdtEnvelope_relayHopDecrement_preservesSenderSignatureValidity() {
        val seed = generateTestKeySeed()
        val blindToken = ByteArray(16)
        val payload = "Relayed envelope through 7 mules".toByteArray(StandardCharsets.UTF_8)

        val env = DtnEnvelope.createAuthenticated(
            blindToken = blindToken,
            encryptedPayload = payload,
            senderPrivateKey = seed,
            hopLimit = 7
        )

        var current = env
        assertEquals(0.toByte(), current.hopCount)
        assertEquals(7.toByte(), current.hopLimit)
        assertTrue(current.verifySignature())

        // Simulate 7 successive data mule physical relays
        for (hop in 1..7) {
            val decremented = current.withDecrementedHop()
            assertNotNull(decremented)
            assertEquals(hop.toByte(), decremented!!.hopCount)
            assertEquals((7 - hop).toByte(), decremented.hopLimit)

            // CRITICAL TEST: Mutable relay modification MUST NOT invalidate Ed25519 signature!
            assertTrue("Hop $hop: Signature must remain valid after hop decrement", decremented.verifySignature())

            // Serialized decremented envelope must pass round-trip verification
            val serialized = decremented.serialize()
            val parsed = DtnEnvelope.deserialize(serialized, verifySignature = true)
            assertNotNull(parsed)
            assertEquals(hop.toByte(), parsed!!.hopCount)
            assertEquals((7 - hop).toByte(), parsed.hopLimit)
            assertTrue(parsed.verifySignature())

            current = decremented
        }

        // Exhausted hop limit (0) cannot decrement further
        assertNull(current.withDecrementedHop())
    }

    @Test
    fun nxdtEnvelope_hopLimitExceededBounds_rejected() {
        val seed = generateTestKeySeed()
        val blindToken = ByteArray(16)
        val env = DtnEnvelope.createAuthenticated(blindToken, "payload".toByteArray(), seed)
        val serialized = env.serialize()

        // Corrupt hopLimit (offset 9) to 16 (exceeds MAX_HOP_LIMIT = 15)
        val invalidHop = serialized.clone()
        invalidHop[9] = 16
        assertNull(DtnEnvelope.deserialize(invalidHop))
    }

    // ==========================================
    // 4. CROSS-PLATFORM DETERMINISTIC TEST VECTORS
    // ==========================================

    @Test
    fun nxdtEnvelope_verifyGoGeneratedVector_succeedsIdentically() {
        // Deterministic Go-generated test vector using RFC 8032 Test Vector 1 Seed
        val serializedHex = "4e584454010101000007000000000000000000000102030405060708090a0b0c0d0e0f10d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a1112131415161718191a1b1c1d1e1f20000000006553f10000000000655694000000002901e6fd2cb448ec1177c2dfdf08486c7c2f287d21a0e736148f041610ffcad9f7c3aaa7749ef420e2c1ff6dbcca37e8c25032cbf673732964709bb0a62a34f9a252b7519999aca745e6e90f1c18f9996c6028831a3894555010971b2c7be1440848656c6c6f204e5844542043726f73732d506c6174666f726d20566572696669656420566563746f72"
        val rawBytes = hexToBytes(serializedHex)

        val parsed = DtnEnvelope.deserialize(rawBytes, verifySignature = true)
        assertNotNull("Go-generated NXDT envelope must parse and verify in Android", parsed)

        assertEquals(DtnConstants.PROTOCOL_VERSION_1, parsed!!.protocolVersion)
        assertEquals(DtnConstants.TYPE_STANDARD_DATA, parsed.envelopeType)
        assertEquals(7.toByte(), parsed.hopLimit)
        assertEquals(0.toByte(), parsed.hopCount)
        assertEquals(1700000000L, parsed.createdAtEpochSec)
        assertEquals(1700172800L, parsed.expiresAtEpochSec)

        val expectedPayload = "Hello NXDT Cross-Platform Verified Vector"
        assertEquals(expectedPayload, String(parsed.encryptedPayload, StandardCharsets.UTF_8))

        val expectedPubKeyHex = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        assertEquals(expectedPubKeyHex, bytesToHex(parsed.senderPublicKey!!))

        val expectedShaHex = "01e6fd2cb448ec1177c2dfdf08486c7c2f287d21a0e736148f041610ffcad9f7"
        assertEquals(expectedShaHex, bytesToHex(parsed.ciphertextSha256!!))

        val expectedSigHex = "c3aaa7749ef420e2c1ff6dbcca37e8c25032cbf673732964709bb0a62a34f9a252b7519999aca745e6e90f1c18f9996c6028831a3894555010971b2c7be14408"
        assertEquals(expectedSigHex, bytesToHex(parsed.signature!!))

        // Verify that Canonical Signing Data generated by Android matches Go byte-for-byte
        val expectedCanonicalHex = "4e584454010101000102030405060708090a0b0c0d0e0f10d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a1112131415161718191a1b1c1d1e1f20000000006553f10000000000655694000000002901e6fd2cb448ec1177c2dfdf08486c7c2f287d21a0e736148f041610ffcad9f7"
        val androidCanonicalHex = bytesToHex(parsed.getCanonicalSigningData())
        assertEquals(expectedCanonicalHex, androidCanonicalHex)

        // Verify signature passes
        assertTrue(parsed.verifySignature())
    }

    @Test
    fun nxdtEnvelope_androidGeneratedVector_matchesExpectedDeterministicSignatures() {
        val seed = hexToBytes("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val uuid = hexToBytes("0102030405060708090a0b0c0d0e0f10")
        val blindToken = hexToBytes("1112131415161718191a1b1c1d1e1f20")
        val payload = "Hello NXDT Cross-Platform Verified Vector".toByteArray(StandardCharsets.UTF_8)

        val env = DtnEnvelope.createAuthenticated(
            blindToken = blindToken,
            encryptedPayload = payload,
            senderPrivateKey = seed,
            hopLimit = 7,
            ttlSeconds = 172800,
            customUuid = uuid
        )
        // Adjust createdAt to exact 1700000000L for vector match
        val canonicalVectorEnv = env.copy(
            createdAtEpochSec = 1700000000L,
            expiresAtEpochSec = 1700172800L
        )

        // Sign over the vector canonical data
        val canonical = canonicalVectorEnv.getCanonicalSigningData()
        val expectedCanonicalHex = "4e584454010101000102030405060708090a0b0c0d0e0f10d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a1112131415161718191a1b1c1d1e1f20000000006553f10000000000655694000000002901e6fd2cb448ec1177c2dfdf08486c7c2f287d21a0e736148f041610ffcad9f7"
        assertEquals(expectedCanonicalHex, bytesToHex(canonical))

        val sig = Ed25519Signer.sign(canonical, seed)
        val finalEnv = canonicalVectorEnv.copy(signature = sig)
        assertTrue(finalEnv.verifySignature())

        val expectedSigHex = "c3aaa7749ef420e2c1ff6dbcca37e8c25032cbf673732964709bb0a62a34f9a252b7519999aca745e6e90f1c18f9996c6028831a3894555010971b2c7be14408"
        assertEquals(expectedSigHex, bytesToHex(sig))
    }

    @Test
    fun nxdtEnvelope_senderIdentityAuthentication_failClosedOnMismatch() {
        val aliceSeed = generateTestKeySeed()
        val alicePub = Ed25519Signer.derivePublicKey(aliceSeed)
        val eveSeed = generateTestKeySeed()
        val evePub = Ed25519Signer.derivePublicKey(eveSeed)

        val blindToken = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val payload = "Confidential E2EE Packet".toByteArray(StandardCharsets.UTF_8)

        val aliceEnv = DtnEnvelope.createAuthenticated(
            blindToken = blindToken,
            encryptedPayload = payload,
            senderPrivateKey = aliceSeed,
            senderPublicKey = alicePub
        )

        // Envelope self-verifies
        assertTrue(aliceEnv.verifySignature())

        // Recipient checks authenticated binding to contact:
        // Case 1: Alice's known contact public key matches envelope sender key -> ACCEPTED
        val isSenderAlice = alicePub.contentEquals(aliceEnv.senderPublicKey)
        assertTrue("Authentic sender public key must match registered contact identity", isSenderAlice)

        // Case 2: Contact identity mismatch (claims to be Alice, but sender key is Eve) -> REJECTED (Fail Closed)
        val isSenderEve = evePub.contentEquals(aliceEnv.senderPublicKey)
        assertFalse("Mismatched sender identity must fail-closed and be rejected", isSenderEve)
    }

    @Test
    fun nxdtEnvelope_attackerSpoofedSenderKey_failsSignatureVerification() {
        val aliceSeed = generateTestKeySeed()
        val alicePub = Ed25519Signer.derivePublicKey(aliceSeed)
        val attackerSeed = generateTestKeySeed()

        val blindToken = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val payload = "Spoofed Payload".toByteArray(StandardCharsets.UTF_8)

        // Attacker creates envelope with attacker's key
        val attackerEnv = DtnEnvelope.createAuthenticated(
            blindToken = blindToken,
            encryptedPayload = payload,
            senderPrivateKey = attackerSeed
        )
        val serialized = attackerEnv.serialize()

        // Attacker replaces senderPublicKey (bytes 36..67) with Alice's public key to impersonate Alice
        val spoofedBytes = serialized.clone()
        System.arraycopy(alicePub, 0, spoofedBytes, 36, 32)

        // Strict deserialization MUST reject spoofed packet because attacker signature was signed over attacker's pubkey
        val parsed = DtnEnvelope.deserialize(spoofedBytes, verifySignature = true)
        assertNull("Spoofed sender key must fail cryptographic verification upon deserialization", parsed)

        val nonStrict = DtnEnvelope.deserialize(spoofedBytes, verifySignature = false)
        assertNotNull(nonStrict)
        assertFalse("Spoofed sender key must fail verifySignature()", nonStrict!!.verifySignature())
    }
}
