package com.nexa.app

import com.google.crypto.tink.subtle.X25519
import com.nexa.app.core.crypto.Ed25519Signer
import com.nexa.app.core.crypto.PrekeyBundle
import com.nexa.app.core.crypto.ProofOfWorkEngine
import com.nexa.app.core.crypto.X3dhHandshakeEngine
import com.nexa.app.core.model.HandshakeEnvelope
import com.nexa.app.core.model.HandshakePayload
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class ContactHandshakeUnitTests {

    private val secureRandom = SecureRandom()

    @Test
    fun testProofOfWorkSolveAndVerify() {
        val targetNxId = "NX-TEST-USER-B"
        val timestamp = System.currentTimeMillis()

        // Solve PoW (computes sequential nonces until 16 leading zero bits)
        val nonce = ProofOfWorkEngine.solve(targetNxId, timestamp)
        assertTrue("Nonce must be non-negative", nonce >= 0)

        // Verify valid PoW
        val isValid = ProofOfWorkEngine.verify(targetNxId, timestamp, nonce)
        assertTrue("Valid nonce must be accepted by verifier in <1us", isValid)

        // Verify invalid nonce rejection
        val isInvalid = ProofOfWorkEngine.verify(targetNxId, timestamp, nonce + 999999L)
        assertFalse("Invalid nonce must be rejected", isInvalid)

        // Verify expired timestamp (> 10 minutes)
        val expiredTimestamp = timestamp - (11 * 60 * 1000L)
        val isExpiredValid = ProofOfWorkEngine.verify(targetNxId, expiredTimestamp, nonce, currentTimeMs = timestamp)
        assertFalse("Expired PoW challenge must be rejected", isExpiredValid)
    }

    @Test
    fun testEd25519SigningAndVerification() {
        val privateKey = ByteArray(32).also { secureRandom.nextBytes(it) }
        val publicKey = Ed25519Signer.derivePublicKey(privateKey)

        assertEquals("Ed25519 public key must be 32 bytes", 32, publicKey.size)

        val message = "Hello from User A with Ed25519 anti-impersonation signature!".toByteArray(Charsets.UTF_8)
        val signature = Ed25519Signer.sign(message, privateKey)

        assertEquals("Ed25519 signature must be strictly 64 bytes", 64, signature.size)

        // Valid signature check
        val verified = Ed25519Signer.verify(message, signature, publicKey)
        assertTrue("Valid Ed25519 signature must verify successfully", verified)

        // Tampered message check
        val tamperedMessage = "Hello from User A with Ed25519 anti-impersonation signature?".toByteArray(Charsets.UTF_8)
        val tamperedVerified = Ed25519Signer.verify(tamperedMessage, signature, publicKey)
        assertFalse("Tampered message must fail signature verification", tamperedVerified)

        // Wrong public key check
        val wrongPublicKey = ByteArray(32).also { secureRandom.nextBytes(it) }
        val wrongKeyVerified = Ed25519Signer.verify(message, signature, wrongPublicKey)
        assertFalse("Verification with wrong public key must fail", wrongKeyVerified)
    }

    @Test
    fun testX3dhBilateralHandshakeExchange() {
        // --- Setup User A (Alice) ---
        val aliceDhPriv = X25519.generatePrivateKey()
        val aliceDhPub = X25519.publicFromPrivate(aliceDhPriv)
        val aliceSignPriv = ByteArray(32).also { secureRandom.nextBytes(it) }
        val aliceSignPub = Ed25519Signer.derivePublicKey(aliceSignPriv)

        // --- Setup User B (Bob) ---
        val bobDhPriv = X25519.generatePrivateKey()
        val bobDhPub = X25519.publicFromPrivate(bobDhPriv)
        val bobSignedPrekeyPriv = X25519.generatePrivateKey()
        val bobSignedPrekeyPub = X25519.publicFromPrivate(bobSignedPrekeyPriv)

        val bobPrekeyBundle = PrekeyBundle(
            nxId = "NX-BOB-99",
            identityKeyHex = toHex(bobDhPub),
            signingKeyHex = toHex(bobDhPub),
            signedPrekeyHex = toHex(bobSignedPrekeyPub),
            signedPrekeySignatureHex = "00",
            oneTimePrekeyHex = null,
            oneTimePrekeyId = null
        )

        // Alice initiates connection request
        val timestamp = System.currentTimeMillis()
        val nonce = ProofOfWorkEngine.solve("NX-BOB-99", timestamp)

        val envelope = X3dhHandshakeEngine.buildInitialEnvelope(
            senderNxId = "NX-ALICE-01",
            senderDisplayName = "Alice Liddell",
            introMessage = "Hey Bob! Let's connect on NEXA.",
            senderIdentityPrivateKey = aliceDhPriv,
            senderIdentityPublicKeyHex = toHex(aliceDhPub),
            senderSigningPrivateKey = aliceSignPriv,
            recipientPrekey = bobPrekeyBundle,
            powNonce = nonce,
            powTimestamp = timestamp,
            isAccept = false
        )

        assertNotNull("Envelope must not be null", envelope)
        assertEquals("NX-ALICE-01", envelope.senderNxId)
        assertFalse(envelope.isAccept)

        // Bob processes inbound envelope
        val decryptedPayload = X3dhHandshakeEngine.processInboundEnvelope(
            envelope = envelope,
            recipientIdentityPrivateKey = bobDhPriv,
            recipientSignedPrivateKey = bobSignedPrekeyPriv,
            senderSigningPublicKey = aliceSignPub
        )

        assertNotNull("Bob must successfully decrypt and authenticate Alice's handshake", decryptedPayload)
        assertEquals("NX-ALICE-01", decryptedPayload!!.senderNxId)
        assertEquals("Alice Liddell", decryptedPayload.displayName)
        assertEquals("Hey Bob! Let's connect on NEXA.", decryptedPayload.introMessage)
        assertFalse(decryptedPayload.isAccept)
    }

    @Test
    fun testX3dhImpersonationSignatureRejection() {
        val aliceDhPriv = X25519.generatePrivateKey()
        val aliceDhPub = X25519.publicFromPrivate(aliceDhPriv)
        val aliceSignPriv = ByteArray(32).also { secureRandom.nextBytes(it) }

        val bobDhPriv = X25519.generatePrivateKey()
        val bobDhPub = X25519.publicFromPrivate(bobDhPriv)
        val bobSignedPrekeyPriv = X25519.generatePrivateKey()
        val bobSignedPrekeyPub = X25519.publicFromPrivate(bobSignedPrekeyPriv)

        val bobPrekeyBundle = PrekeyBundle(
            nxId = "NX-BOB-99",
            identityKeyHex = toHex(bobDhPub),
            signingKeyHex = toHex(bobDhPub),
            signedPrekeyHex = toHex(bobSignedPrekeyPub),
            signedPrekeySignatureHex = "00"
        )

        val envelope = X3dhHandshakeEngine.buildInitialEnvelope(
            senderNxId = "NX-ALICE-01",
            senderDisplayName = "Alice",
            introMessage = "Hello",
            senderIdentityPrivateKey = aliceDhPriv,
            senderIdentityPublicKeyHex = toHex(aliceDhPub),
            senderSigningPrivateKey = aliceSignPriv,
            recipientPrekey = bobPrekeyBundle,
            powNonce = 42L,
            powTimestamp = System.currentTimeMillis()
        )

        // Bob tries to verify with an attacker's wrong public key
        val attackerSignPub = ByteArray(32).also { secureRandom.nextBytes(it) }
        val result = X3dhHandshakeEngine.processInboundEnvelope(
            envelope = envelope,
            recipientIdentityPrivateKey = bobDhPriv,
            recipientSignedPrivateKey = bobSignedPrekeyPriv,
            senderSigningPublicKey = attackerSignPub
        )

        assertNull("Handshake with spoofed/invalid Ed25519 identity signature must be rejected immediately", result)
    }

    @Test
    fun testHandshakeEnvelopeJsonRoundtrip() {
        val envelope = HandshakeEnvelope(
            senderNxId = "NX-USER-77",
            ephemeralPublicKeyHex = "aabbcc",
            identityPublicKeyHex = "ddeeff",
            oneTimePrekeyId = "opk_1",
            saltHex = "112233",
            ivHex = "445566",
            ciphertextHex = "778899",
            signatureHex = "aabbccddeeff",
            powTimestamp = 1700000000L,
            powNonce = 12345L,
            isAccept = false
        )

        val json = envelope.toJson()
        val parsed = HandshakeEnvelope.fromJson(json)

        assertNotNull(parsed)
        assertEquals(envelope.senderNxId, parsed!!.senderNxId)
        assertEquals(envelope.ephemeralPublicKeyHex, parsed.ephemeralPublicKeyHex)
        assertEquals(envelope.ciphertextHex, parsed.ciphertextHex)
        assertEquals(envelope.signatureHex, parsed.signatureHex)
        assertEquals(envelope.powNonce, parsed.powNonce)
        assertEquals(envelope.isAccept, parsed.isAccept)
    }

    @Test
    fun testContactEntityStateTransitions() {
        val contact = ContactEntity(
            nxId = "NX-CHARLIE",
            displayName = "Charlie",
            publicKeyHex = "abcdef123456",
            introMessage = "Let's chat",
            status = ContactStatus.REQUEST_SENT
        )

        assertEquals(ContactStatus.REQUEST_SENT, contact.status)

        val received = contact.copy(status = ContactStatus.REQUEST_RECEIVED)
        assertEquals(ContactStatus.REQUEST_RECEIVED, received.status)

        val mutual = received.copy(status = ContactStatus.MUTUAL_FRIEND)
        assertEquals(ContactStatus.MUTUAL_FRIEND, mutual.status)

        val blocked = mutual.copy(status = ContactStatus.BLOCKED)
        assertEquals(ContactStatus.BLOCKED, blocked.status)
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
