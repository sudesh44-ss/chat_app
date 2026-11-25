package com.nexa.app

import com.google.crypto.tink.subtle.X25519
import com.nexa.app.core.sms.SmsBlockReason
import com.nexa.app.core.sms.SmsEligibilityEvaluator
import com.nexa.app.core.sms.SmsEligibilityInput
import com.nexa.app.core.sms.SmsFailureClassifier
import com.nexa.app.core.sms.SmsFailureKind
import com.nexa.app.core.sms.SmsFallbackState
import com.nexa.app.core.sms.SmsSimSelectionMode
import com.nexa.app.core.sms.SmsSubscriptionPolicy
import com.nexa.app.core.sms.SmsSubscriptionSlot
import com.nexa.app.core.sms.maskPhoneNumber
import com.nexa.app.core.sms.nx1.InMemorySmsInboundPartStore
import com.nexa.app.core.sms.nx1.Nx1
import com.nexa.app.core.sms.nx1.Nx1AckPacket
import com.nexa.app.core.sms.nx1.Nx1Base64Url
import com.nexa.app.core.sms.nx1.Nx1Cipher
import com.nexa.app.core.sms.nx1.Nx1Codec
import com.nexa.app.core.sms.nx1.Nx1DecryptFailure
import com.nexa.app.core.sms.nx1.Nx1DecryptResult
import com.nexa.app.core.sms.nx1.Nx1DirectionKeys
import com.nexa.app.core.sms.nx1.Nx1InboundPart
import com.nexa.app.core.sms.nx1.Nx1KeySchedule
import com.nexa.app.core.sms.nx1.Nx1MessagePacket
import com.nexa.app.core.sms.nx1.Nx1ParseResult
import com.nexa.app.core.sms.nx1.SmsAssemblyStatus
import com.nexa.app.core.sms.nx1.SmsMultipartAssembler
import com.nexa.app.core.sms.nx1.SmsReplayWindow
import com.nexa.app.core.sms.nx1.SmsSegmentCalculator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * Module 3 — Encrypted SMS fallback security & protocol unit tests.
 * Pure JVM: no Android framework, no database, no network.
 */
class Module3EncryptedSmsFallbackTests {

    private val aliceNxId = "NX-ALICE-0001"
    private val bobNxId = "NX-BOB-0002"

    private fun keysFor(keyVersion: Int = 1): Pair<Nx1DirectionKeys, Nx1DirectionKeys> {
        val alicePriv = X25519.generatePrivateKey()
        val alicePub = X25519.publicFromPrivate(alicePriv)
        val bobPriv = X25519.generatePrivateKey()
        val bobPub = X25519.publicFromPrivate(bobPriv)

        val aliceShared = X25519.computeSharedSecret(alicePriv, bobPub)
        val bobShared = X25519.computeSharedSecret(bobPriv, alicePub)

        val aliceRoot = Nx1KeySchedule.deriveSessionRoot(aliceShared, aliceNxId, bobNxId, keyVersion)
        val bobRoot = Nx1KeySchedule.deriveSessionRoot(bobShared, bobNxId, aliceNxId, keyVersion)

        return Nx1KeySchedule.deriveDirectionKeys(aliceRoot, aliceNxId, bobNxId, keyVersion) to
            Nx1KeySchedule.deriveDirectionKeys(bobRoot, bobNxId, aliceNxId, keyVersion)
    }

    private fun encryptedPayload(
        keys: Nx1DirectionKeys,
        messageId: String,
        counter: Long = 0,
        text: String = "Hello from an offline SMS"
    ): ByteArray = Nx1Cipher.encryptPart(
        keys = keys,
        counter = counter,
        messageId = messageId,
        senderNxId = aliceNxId,
        recipientNxId = bobNxId,
        plaintext = text.toByteArray(StandardCharsets.UTF_8)
    )

    // ---------------------------------------------------------------- NX1 protocol

    @Test
    fun nx1_singlePart_roundTrip() {
        val messageId = Nx1Codec.newMessageId()
        val payload = ByteArray(64) { it.toByte() }
        val token = Nx1Codec.encodeMessage(
            Nx1MessagePacket(messageId = messageId, partIndex = 0, totalParts = 1, payload = payload)
        )
        assertTrue(token.startsWith("NX1:1:$messageId:1/1:"))

        val parsed = Nx1Codec.parse(token)
        assertTrue(parsed is Nx1ParseResult.Message)
        val packet = (parsed as Nx1ParseResult.Message).packet
        assertEquals(messageId, packet.messageId)
        assertEquals(0, packet.partIndex)
        assertEquals(1, packet.totalParts)
        assertArrayEquals(payload, packet.payload)
    }

    @Test
    fun nx1_unsupportedVersion_isRejected() {
        val result = Nx1Codec.parse("NX1:9:${Nx1Codec.newMessageId()}:1/1:AAAA")
        assertEquals(Nx1ParseResult.Invalid("UNSUPPORTED_VERSION"), result)
    }

    @Test
    fun nx1_malformedBase64_isRejected() {
        val result = Nx1Codec.parse("NX1:1:${Nx1Codec.newMessageId()}:1/1:!!!!not-base64")
        assertTrue(result is Nx1ParseResult.Invalid)
    }

    @Test
    fun nx1_paddingAndIllegalAlphabet_areRejected() {
        assertNull(Nx1Base64Url.decode("AAA="))
        assertNull(Nx1Base64Url.decode("AA+A"))
        assertNull(Nx1Base64Url.decode("A"))
    }

    @Test
    fun nx1_oversizedToken_isRejectedWithoutAllocation() {
        val huge = "A".repeat(Nx1.MAX_PART_TOKEN_CHARS + 10)
        val result = Nx1Codec.parse("NX1:1:${Nx1Codec.newMessageId()}:1/1:$huge")
        assertEquals(Nx1ParseResult.Invalid("OVERSIZED_TOKEN"), result)
    }

    @Test
    fun nx1_partIndexBeyondTotal_isRejected() {
        val result = Nx1Codec.parse("NX1:1:${Nx1Codec.newMessageId()}:3/2:AAAA")
        assertEquals(Nx1ParseResult.Invalid("INVALID_PART_INDEX"), result)
    }

    @Test
    fun nx1_tooManyParts_isRejected() {
        val result = Nx1Codec.parse("NX1:1:${Nx1Codec.newMessageId()}:1/9:AAAA")
        assertEquals(Nx1ParseResult.Invalid("INVALID_TOTAL_PARTS"), result)
    }

    @Test
    fun nx1_encodeRejectsOversizedPayload() {
        try {
            Nx1Codec.encodeMessage(
                Nx1MessagePacket(
                    messageId = Nx1Codec.newMessageId(),
                    partIndex = 0,
                    totalParts = 1,
                    payload = ByteArray(Nx1.MAX_CIPHERTEXT_BYTES + 1)
                )
            )
            fail("Encoding an oversized payload must fail")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun nx1_extractTokensFromConcatenatedSmsBody_ignoresSurroundingText() {
        val id = Nx1Codec.newMessageId()
        val p1 = Nx1Codec.encodeMessage(
            Nx1MessagePacket(messageId = id, partIndex = 0, totalParts = 2, payload = ByteArray(20) { 1 })
        )
        val p2 = Nx1Codec.encodeMessage(
            Nx1MessagePacket(messageId = id, partIndex = 1, totalParts = 2, payload = ByteArray(20) { 2 })
        )
        val body = "Hey, NEXA fallback:\n$p1\n$p2"
        val tokens = Nx1Codec.extractTokens(body)
        assertEquals(2, tokens.size)
        assertEquals(p1, tokens[0])
        assertEquals(p2, tokens[1])
    }

    @Test
    fun nx1_ack_roundTripAndMalformedLengthRejected() {
        val messageId = Nx1Codec.newMessageId()
        val ack = Nx1AckPacket(messageId = messageId, counter = 42L, authTag = ByteArray(16) { 7 })
        val token = Nx1Codec.encodeAck(ack)
        val parsed = Nx1Codec.parse(token)
        assertTrue(parsed is Nx1ParseResult.Ack)
        val decoded = (parsed as Nx1ParseResult.Ack).packet
        assertEquals(messageId, decoded.messageId)
        assertEquals(42L, decoded.counter)
        assertArrayEquals(ack.authTag, decoded.authTag)

        val short = Nx1Codec.parse("NX1:2:$messageId:ACK:AAAA")
        assertEquals(Nx1ParseResult.Invalid("MALFORMED_ACK_LENGTH"), short)
    }

    @Test
    fun nx1_messageIdEntropy_is96Bits() {
        val ids = (1..200).map { Nx1Codec.newMessageId(SecureRandom()) }
        assertEquals(200, ids.toSet().size)
        assertTrue(ids.all { Nx1Codec.isValidMessageId(it) })
        assertFalse(Nx1Codec.isValidMessageId("short"))
    }

    // ---------------------------------------------------------------- SMS cryptography

    @Test
    fun smsSession_directionKeysAreIndependentButMirrored() {
        val (alice, bob) = keysFor()
        assertArrayEquals(alice.sendingKey, bob.receivingKey)
        assertArrayEquals(bob.sendingKey, alice.receivingKey)
        assertFalse(alice.sendingKey.contentEquals(alice.receivingKey))
        assertFalse(alice.ackSendingKey.contentEquals(alice.sendingKey))
    }

    @Test
    fun smsSession_encryptDecryptAcrossDevices_succeeds() {
        val (alice, bob) = keysFor()
        val messageId = Nx1Codec.newMessageId()
        val payload = encryptedPayload(alice, messageId, 0, "Offline secret ✓")

        val result = Nx1Cipher.decryptPart(bob, messageId, aliceNxId, bobNxId, payload)
        assertTrue(result is Nx1DecryptResult.Success)
        val success = result as Nx1DecryptResult.Success
        assertEquals("Offline secret ✓", String(success.plaintext, StandardCharsets.UTF_8))
        assertEquals(0L, success.counter)
    }

    @Test
    fun smsSession_payloadDoesNotContainPlaintext() {
        val (alice, _) = keysFor()
        val payload = encryptedPayload(alice, Nx1Codec.newMessageId(), 0, "TOPSECRET-PLAINTEXT")
        val asLatin = String(payload, StandardCharsets.ISO_8859_1)
        assertFalse(asLatin.contains("TOPSECRET-PLAINTEXT"))
    }

    @Test
    fun smsSession_modifiedCiphertext_failsAuthentication() {
        val (alice, bob) = keysFor()
        val messageId = Nx1Codec.newMessageId()
        val payload = encryptedPayload(alice, messageId)
        payload[payload.size - 20] = (payload[payload.size - 20].toInt() xor 0x01).toByte()

        val result = Nx1Cipher.decryptPart(bob, messageId, aliceNxId, bobNxId, payload)
        assertEquals(
            Nx1DecryptFailure.AUTHENTICATION_FAILED,
            (result as Nx1DecryptResult.Failure).reason
        )
    }

    @Test
    fun smsSession_modifiedAuthTag_failsAuthentication() {
        val (alice, bob) = keysFor()
        val messageId = Nx1Codec.newMessageId()
        val payload = encryptedPayload(alice, messageId)
        payload[payload.size - 1] = (payload[payload.size - 1].toInt() xor 0xFF).toByte()

        val result = Nx1Cipher.decryptPart(bob, messageId, aliceNxId, bobNxId, payload)
        assertEquals(Nx1DecryptFailure.AUTHENTICATION_FAILED, (result as Nx1DecryptResult.Failure).reason)
    }

    @Test
    fun smsSession_tamperedNonce_isRejected() {
        val (alice, bob) = keysFor()
        val messageId = Nx1Codec.newMessageId()
        val payload = encryptedPayload(alice, messageId)
        payload[12] = (payload[12].toInt() xor 0x01).toByte()

        val result = Nx1Cipher.decryptPart(bob, messageId, aliceNxId, bobNxId, payload)
        assertEquals(Nx1DecryptFailure.AUTHENTICATION_FAILED, (result as Nx1DecryptResult.Failure).reason)
    }

    @Test
    fun smsSession_counterIsBoundIntoAad_soReusedCounterFails() {
        val (alice, bob) = keysFor()
        val messageId = Nx1Codec.newMessageId()
        val payload = encryptedPayload(alice, messageId, counter = 5)
        // Counter bytes [3..10] are authenticated as AAD; altering them must fail.
        payload[10] = (payload[10].toInt() xor 0x02).toByte()
        val result = Nx1Cipher.decryptPart(bob, messageId, aliceNxId, bobNxId, payload)
        assertTrue(result is Nx1DecryptResult.Failure)
    }

    @Test
    fun smsSession_wrongSenderIdentity_fails() {
        val (alice, bob) = keysFor()
        val messageId = Nx1Codec.newMessageId()
        val payload = encryptedPayload(alice, messageId)
        // Sender identity is bound in the AAD: claiming another sender must fail.
        val result = Nx1Cipher.decryptPart(bob, messageId, "NX-MALLORY-9999", bobNxId, payload)
        assertEquals(Nx1DecryptFailure.AUTHENTICATION_FAILED, (result as Nx1DecryptResult.Failure).reason)
    }

    @Test
    fun smsSession_wrongRecipientIdentity_fails() {
        val (alice, bob) = keysFor()
        val messageId = Nx1Codec.newMessageId()
        val payload = encryptedPayload(alice, messageId)
        val result = Nx1Cipher.decryptPart(bob, messageId, aliceNxId, "NX-OTHER-0003", payload)
        assertEquals(Nx1DecryptFailure.AUTHENTICATION_FAILED, (result as Nx1DecryptResult.Failure).reason)
    }

    @Test
    fun smsSession_keyVersionMismatch_isRejected() {
        val (alice, _) = keysFor(keyVersion = 1)
        val (_, bobV2) = keysFor(keyVersion = 2)
        val messageId = Nx1Codec.newMessageId()
        val payload = encryptedPayload(alice, messageId)

        val result = Nx1Cipher.decryptPart(bobV2, messageId, aliceNxId, bobNxId, payload)
        assertEquals(Nx1DecryptFailure.KEY_VERSION_MISMATCH, (result as Nx1DecryptResult.Failure).reason)
    }

    @Test
    fun smsSession_rotationWithNewVersion_breaksOldCiphertext() {
        val (aliceV1, _) = keysFor(keyVersion = 1)
        val (_, bobV2) = keysFor(keyVersion = 2)
        val messageId = Nx1Codec.newMessageId()
        val oldPayload = encryptedPayload(aliceV1, messageId)
        val result = Nx1Cipher.decryptPart(bobV2, messageId, aliceNxId, bobNxId, oldPayload)
        assertTrue(result is Nx1DecryptResult.Failure)
    }

    @Test
    fun smsSession_noncesAreUniquePerCounter() {
        val a = Nx1KeySchedule.deriveNonce(1, 1)
        val b = Nx1KeySchedule.deriveNonce(1, 2)
        val c = Nx1KeySchedule.deriveNonce(2, 1)
        assertFalse(a.contentEquals(b))
        assertFalse(a.contentEquals(c))
        assertEquals(12, a.size)
    }

    // ---------------------------------------------------------------- Replay window

    @Test
    fun replayWindow_acceptsFresh_rejectsDuplicate_andSupportsOutOfOrder() {
        val window = SmsReplayWindow()
        assertTrue(window.accept(0))
        assertFalse("duplicate must be rejected", window.accept(0))
        assertTrue(window.accept(5))
        assertTrue("older but unseen counter inside window is allowed", window.accept(2))
        assertFalse(window.accept(2))
        assertTrue(window.accept(100))
        assertFalse("counter older than the 64-slot window is rejected", window.accept(1))
    }

    @Test
    fun replayWindow_survivesSerialization() {
        val window = SmsReplayWindow()
        window.accept(7)
        window.accept(3)
        val restored = SmsReplayWindow.parse(window.serialize())
        assertFalse(restored.accept(7))
        assertFalse(restored.accept(3))
        assertTrue(restored.accept(8))
    }

    // ---------------------------------------------------------------- ACK authentication

    @Test
    fun ack_validTagIsAccepted_forgedTagIsRejected() {
        val (alice, bob) = keysFor()
        val messageId = Nx1Codec.newMessageId()
        // Bob acknowledges Alice's message: Bob -> Alice direction.
        val ack = Nx1Cipher.createAck(bob, 0, messageId, bobNxId, aliceNxId)
        assertTrue(Nx1Cipher.verifyAck(alice, ack, bobNxId, aliceNxId))

        val forged = Nx1AckPacket(messageId, 1L, ByteArray(16) { 0x41 })
        assertFalse(Nx1Cipher.verifyAck(alice, forged, bobNxId, aliceNxId))
    }

    @Test
    fun ack_cannotBeReusedForAnotherMessageOrSender() {
        val (alice, bob) = keysFor()
        val messageId = Nx1Codec.newMessageId()
        val ack = Nx1Cipher.createAck(bob, 0, messageId, bobNxId, aliceNxId)

        val differentMessage = Nx1AckPacket(Nx1Codec.newMessageId(), ack.counter, ack.authTag)
        assertFalse(Nx1Cipher.verifyAck(alice, differentMessage, bobNxId, aliceNxId))
        assertFalse(Nx1Cipher.verifyAck(alice, ack, "NX-MALLORY-9999", aliceNxId))
    }

    // ---------------------------------------------------------------- Multipart

    private val testNow = 1_700_000_000_000L

    private fun part(
        id: String,
        index: Int,
        total: Int,
        byte: Byte,
        sender: String = aliceNxId,
        at: Long = testNow
    ) = Nx1InboundPart(id, sender, index, total, ByteArray(16) { byte }, at)

    @Test
    fun multipart_outOfOrderCompletion_yieldsExactlyOneAssembledMessage() = runBlocking {
        val assembler = SmsMultipartAssembler(InMemorySmsInboundPartStore())
        val id = Nx1Codec.newMessageId()

        val first = assembler.accept(part(id, 2, 3, 3), testNow)
        assertEquals(SmsAssemblyStatus.INCOMPLETE, first.status)
        assertEquals(1, first.receivedParts)

        val second = assembler.accept(part(id, 0, 3, 1), testNow)
        assertEquals(SmsAssemblyStatus.INCOMPLETE, second.status)

        val third = assembler.accept(part(id, 1, 3, 2), testNow)
        assertEquals(SmsAssemblyStatus.COMPLETE, third.status)
        assertEquals(48, third.assembled!!.size)
        assertEquals(1.toByte(), third.assembled!![0])
        assertEquals(2.toByte(), third.assembled!![16])
        assertEquals(3.toByte(), third.assembled!![32])
    }

    @Test
    fun multipart_duplicatePartIsIdempotent_conflictingDuplicateDropsMessage() = runBlocking {
        val store = InMemorySmsInboundPartStore()
        val assembler = SmsMultipartAssembler(store)
        val id = Nx1Codec.newMessageId()

        assembler.accept(part(id, 0, 2, 1), testNow)
        val duplicate = assembler.accept(part(id, 0, 2, 1), testNow)
        assertEquals(SmsAssemblyStatus.DUPLICATE, duplicate.status)

        val conflict = assembler.accept(part(id, 0, 2, 9), testNow)
        assertEquals(SmsAssemblyStatus.REJECTED, conflict.status)
        assertEquals("CONFLICTING_PART", conflict.reason)
        assertTrue("conflicting message must be discarded", store.partsFor(id, aliceNxId).isEmpty())
    }

    @Test
    fun multipart_missingPartNeverCompletes() = runBlocking {
        val assembler = SmsMultipartAssembler(InMemorySmsInboundPartStore())
        val id = Nx1Codec.newMessageId()
        assembler.accept(part(id, 0, 3, 1), testNow)
        val result = assembler.accept(part(id, 2, 3, 3), testNow)
        assertEquals(SmsAssemblyStatus.INCOMPLETE, result.status)
        assertEquals(2, result.receivedParts)
        assertNull(result.assembled)
    }

    @Test
    fun multipart_rejectsTooManyPartsAndOversizedParts() = runBlocking {
        val assembler = SmsMultipartAssembler(InMemorySmsInboundPartStore())
        val id = Nx1Codec.newMessageId()
        assertEquals(SmsAssemblyStatus.REJECTED, assembler.accept(part(id, 0, Nx1.MAX_PARTS + 1, 1), testNow).status)

        val oversized = Nx1InboundPart(id, aliceNxId, 0, 1, ByteArray(Nx1.MAX_CIPHERTEXT_BYTES + 1), testNow)
        assertEquals(SmsAssemblyStatus.REJECTED, assembler.accept(oversized, testNow).status)
    }

    @Test
    fun multipart_expiresIncompleteMessages_andBoundsConcurrentBuffers() = runBlocking {
        val assembler = SmsMultipartAssembler(
            store = InMemorySmsInboundPartStore(),
            maxConcurrentMessages = 2,
            ttlMillis = 1_000L
        )
        val firstId = Nx1Codec.newMessageId()
        assembler.accept(part(firstId, 0, 2, 1), testNow)
        assembler.accept(part(Nx1Codec.newMessageId(), 0, 2, 1), testNow)
        val third = assembler.accept(part(Nx1Codec.newMessageId(), 0, 2, 1), testNow)
        assertEquals(SmsAssemblyStatus.REJECTED, third.status)
        assertEquals("TOO_MANY_CONCURRENT_MESSAGES", third.reason)

        // After the TTL passes, stale sessions are purged and new ones are accepted again.
        val later = testNow + 10_000L
        val accepted = assembler.accept(part(Nx1Codec.newMessageId(), 0, 2, 1, at = later), later)
        assertEquals(SmsAssemblyStatus.INCOMPLETE, accepted.status)
    }

    @Test
    fun multipart_inconsistentTotalPartsIsRejected() = runBlocking {
        val assembler = SmsMultipartAssembler(InMemorySmsInboundPartStore())
        val id = Nx1Codec.newMessageId()
        assembler.accept(part(id, 0, 2, 1), testNow)
        val inconsistent = assembler.accept(part(id, 1, 3, 2), testNow)
        assertEquals(SmsAssemblyStatus.REJECTED, inconsistent.status)
        assertEquals("INCONSISTENT_TOTAL_PARTS", inconsistent.reason)
    }

    // ---------------------------------------------------------------- Segmentation

    @Test
    fun segmentation_gsm7AndUnicodeLimits() {
        assertEquals(1, SmsSegmentCalculator.segmentCount("A".repeat(160)))
        assertEquals(2, SmsSegmentCalculator.segmentCount("A".repeat(161)))
        assertEquals(2, SmsSegmentCalculator.segmentCount("A".repeat(306)))
        assertEquals(3, SmsSegmentCalculator.segmentCount("A".repeat(307)))

        // 'é' is part of the GSM-7 default alphabet, so it stays in single-septet accounting.
        assertEquals(1, SmsSegmentCalculator.segmentCount("é".repeat(160)))
        // CJK/emoji are outside GSM-7 and use UCS-2 limits (70 / 67).
        assertEquals(1, SmsSegmentCalculator.segmentCount("漢".repeat(70)))
        assertEquals(2, SmsSegmentCalculator.segmentCount("漢".repeat(71)))
        assertEquals(1, SmsSegmentCalculator.segmentCount("Hello 😀"))
    }

    @Test
    fun segmentation_nx1PacketIsGsm7() {
        val token = Nx1Codec.encodeMessage(
            Nx1MessagePacket(
                messageId = Nx1Codec.newMessageId(),
                partIndex = 0,
                totalParts = 1,
                payload = ByteArray(120) { 0x2A }
            )
        )
        assertTrue("NX1 tokens must stay in the GSM-7 alphabet", SmsSegmentCalculator.isGsm7(token))
    }

    // ---------------------------------------------------------------- Eligibility / group protection

    private fun input(
        group: Boolean = false,
        status: String? = "MUTUAL_FRIEND",
        phone: Boolean = true,
        visible: Boolean = true,
        sessionReady: Boolean = true,
        expired: Boolean = false,
        revoked: Boolean = false,
        sim: Boolean = true,
        payloadWithinLimits: Boolean = true
    ) = SmsEligibilityInput(
        isGroupConversation = group,
        contactStatusName = status,
        hasPeerPhoneNumber = phone,
        phoneNumberVisibleToUser = visible,
        sessionReady = sessionReady,
        sessionExpired = expired,
        sessionRevoked = revoked,
        simAvailable = sim,
        payloadWithinLimits = payloadWithinLimits
    )

    @Test
    fun eligibility_groupConversationsAreAlwaysBlocked() {
        val result = SmsEligibilityEvaluator.evaluate(input(group = true))
        assertFalse(result.eligible)
        assertEquals(SmsFallbackState.SMS_GROUP_DISABLED, result.state)
        assertEquals(SmsBlockReason.GROUP_CHAT, result.reason)
    }

    @Test
    fun eligibility_requiresAcceptedContact() {
        assertEquals(SmsBlockReason.CONTACT_NOT_ACCEPTED, SmsEligibilityEvaluator.evaluate(input(status = "REQUEST_SENT")).reason)
        assertEquals(SmsBlockReason.CONTACT_BLOCKED, SmsEligibilityEvaluator.evaluate(input(status = "BLOCKED")).reason)
    }

    @Test
    fun eligibility_requiresPhoneNumberAndSession() {
        assertEquals(SmsBlockReason.PHONE_NUMBER_UNAVAILABLE, SmsEligibilityEvaluator.evaluate(input(phone = false)).reason)
        assertEquals(SmsBlockReason.PHONE_NUMBER_UNAVAILABLE, SmsEligibilityEvaluator.evaluate(input(visible = false)).reason)
        val missing = SmsEligibilityEvaluator.evaluate(input(sessionReady = false))
        assertEquals(SmsBlockReason.SESSION_MISSING, missing.reason)
        assertTrue(missing.userMessage.contains("Connect to the internet once"))
        assertEquals(SmsBlockReason.SESSION_EXPIRED, SmsEligibilityEvaluator.evaluate(input(expired = true)).reason)
        assertEquals(SmsBlockReason.SESSION_REVOKED, SmsEligibilityEvaluator.evaluate(input(revoked = true)).reason)
        assertEquals(SmsBlockReason.PAYLOAD_TOO_LARGE, SmsEligibilityEvaluator.evaluate(input(payloadWithinLimits = false)).reason)
        assertEquals(SmsBlockReason.SIM_UNAVAILABLE, SmsEligibilityEvaluator.evaluate(input(sim = false)).reason)
    }

    @Test
    fun eligibility_directChatBecomesReadyWhenFullyProvisioned() {
        val result = SmsEligibilityEvaluator.evaluate(input())
        assertTrue(result.eligible)
        assertEquals(SmsFallbackState.SMS_READY, result.state)

        val online = SmsEligibilityEvaluator.evaluate(
            input().copy(internetAvailable = true)
        )
        assertEquals(SmsFallbackState.INTERNET_ONLINE, online.state)
    }

    @Test
    fun phoneNumberMasking_neverRevealsTheFullNumber() {
        val masked = maskPhoneNumber("+919876543210")
        assertFalse(masked.contains("987654"))
        assertTrue(masked.endsWith("10"))
    }

    // ---------------------------------------------------------------- Failure classification

    @Test
    fun failureClassifier_neverClaimsUnverifiedCauses() {
        val generic = SmsFailureClassifier.classify(SmsFailureClassifier.RESULT_ERROR_GENERIC_FAILURE)
        assertEquals(SmsFailureKind.UNKNOWN_CARRIER, generic.kind)
        assertTrue(generic.userMessage.contains("carrier restrictions"))
        assertFalse(generic.userMessage.contains("recharge"))

        assertEquals(SmsFailureKind.RADIO_UNAVAILABLE, SmsFailureClassifier.classify(SmsFailureClassifier.RESULT_ERROR_RADIO_OFF).kind)
        assertEquals(SmsFailureKind.NO_SERVICE, SmsFailureClassifier.classify(SmsFailureClassifier.RESULT_ERROR_NO_SERVICE).kind)
        assertEquals(SmsFailureKind.RATE_LIMITED, SmsFailureClassifier.classify(SmsFailureClassifier.RESULT_RATE_LIMITED).kind)
        assertEquals(SmsFailureKind.INVALID_DESTINATION, SmsFailureClassifier.classify(SmsFailureClassifier.RESULT_ERROR_INVALID_ARGUMENT).kind)
        assertEquals(SmsFailureKind.UNKNOWN_CARRIER, SmsFailureClassifier.classify(-1).kind)
    }

    @Test
    fun modeA_reportsUnconfirmedNeverDelivered() {
        val failure = SmsFailureClassifier.modeAUnconfirmed()
        assertEquals(SmsFailureKind.MODE_A_UNCONFIRMED, failure.kind)
        assertEquals("SMS sent — delivery confirmation unavailable.", failure.userMessage)
        assertTrue(failure.canQueueForInternet)
    }

    // ---------------------------------------------------------------- Dual-SIM policy

    @Test
    fun simPolicy_withoutSubscriptionAccess_isDelegatedToSystemSmsApp() {
        val selection = SmsSubscriptionPolicy.resolve(null, null, null)
        assertEquals(SmsSimSelectionMode.DELEGATED_TO_SYSTEM_SMS_APP, selection.mode)
        assertFalse(selection.mustAskUserBeforeSending)
        assertNull(selection.selected)
    }

    @Test
    fun simPolicy_noSimsIsReported() {
        val selection = SmsSubscriptionPolicy.resolve(emptyList(), null, null)
        assertEquals(SmsSimSelectionMode.NO_SIM, selection.mode)
        assertTrue(selection.userMessage.contains("No SIM"))
    }

    @Test
    fun simPolicy_prefersExplicitThenLastUsedThenSystemDefault() {
        val slots = listOf(
            SmsSubscriptionSlot(1, 0, "CarrierA", false),
            SmsSubscriptionSlot(2, 1, "CarrierB", true)
        )
        assertEquals(2, SmsSubscriptionPolicy.resolve(slots, null, null).selected?.subscriptionId) // system default
        assertEquals(1, SmsSubscriptionPolicy.resolve(slots, 1, 2).selected?.subscriptionId) // explicit preference
        assertEquals(2, SmsSubscriptionPolicy.resolve(slots, null, 2).selected?.subscriptionId) // last used
    }

    @Test
    fun simPolicy_removedSimRequiresUserChoice_neverSilentSwitch() {
        val slots = listOf(SmsSubscriptionSlot(5, 1, "CarrierB", false))
        val selection = SmsSubscriptionPolicy.resolve(slots, preferredSubscriptionId = 3, lastUsedSubscriptionId = 3)
        assertEquals(SmsSimSelectionMode.REQUIRES_USER_CHOICE, selection.mode)
        assertTrue(selection.mustAskUserBeforeSending)
        assertNull(selection.selected)
    }
}
