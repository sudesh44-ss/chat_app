package com.nexa.app

import com.nexa.app.core.dtn.BlindTokenGenerator
import com.nexa.app.core.dtn.DtnBloomFilter
import com.nexa.app.core.dtn.DtnConstants
import com.nexa.app.core.dtn.DtnEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Random

class DtnMeshUnitTests {

    @Test
    fun dtnConstants_limits_conformToSpecification() {
        assertEquals(7.toByte(), DtnConstants.DEFAULT_HOP_LIMIT)
        assertEquals(48L * 3600L, DtnConstants.DEFAULT_TTL_SECONDS)
        assertEquals(500, DtnConstants.MAX_CACHE_ENVELOPE_COUNT)
        assertEquals(64, DtnConstants.BLOOM_FILTER_BYTE_SIZE)
        assertEquals(45, DtnConstants.ENVELOPE_HEADER_SIZE)
    }

    @Test
    fun dtnEnvelope_serializationAndDeserialization_preservesBinaryIntegrity() {
        val envId = ByteArray(16).also { Random(1).nextBytes(it) }
        val blindToken = ByteArray(16).also { Random(2).nextBytes(it) }
        val hopLimit: Byte = 7
        val expiryEpochSec = 1735689600L
        val payload = "Hello via Autonomous Data Mule Mesh".toByteArray(StandardCharsets.UTF_8)

        val envelope = DtnEnvelope(
            envelopeId = envId,
            blindToken = blindToken,
            hopLimit = hopLimit,
            expiresAtEpochSec = expiryEpochSec,
            encryptedPayload = payload
        )

        val serialized = envelope.serialize()
        assertEquals(45 + payload.size, serialized.size)

        val deserialized = DtnEnvelope.deserialize(serialized)
        assertNotNull(deserialized)
        assertTrue(envelope.envelopeId.contentEquals(deserialized!!.envelopeId))
        assertTrue(envelope.blindToken.contentEquals(deserialized.blindToken))
        assertEquals(envelope.hopLimit, deserialized.hopLimit)
        assertEquals(envelope.expiresAtEpochSec, deserialized.expiresAtEpochSec)
        assertTrue(envelope.encryptedPayload.contentEquals(deserialized.encryptedPayload))
    }

    @Test
    fun dtnEnvelope_truncatedOrMalformedBytes_returnsNullSafely() {
        // Less than 45 bytes minimum header
        val truncated = ByteArray(40)
        assertNull(DtnEnvelope.deserialize(truncated))

        // Header claims 100 bytes payload, but only 10 provided
        val corrupt = ByteArray(55)
        corrupt[41] = 0x00
        corrupt[42] = 0x00
        corrupt[43] = 0x00
        corrupt[44] = 0x64 // 100 in big-endian int
        assertNull(DtnEnvelope.deserialize(corrupt))
    }

    @Test
    fun dtnBloomFilter_addAndContains_hasZeroFalseNegatives() {
        val filter = DtnBloomFilter()
        val keys = (1..50).map { "envelope_id_hash_00$it" }

        // Initially does not contain any keys
        for (k in keys) {
            assertFalse(filter.contains(k))
        }

        // Add all keys
        for (k in keys) {
            filter.add(k)
        }

        // Zero false negatives: all added keys MUST return true
        for (k in keys) {
            assertTrue("Key $k must be recognized in Bloom filter", filter.contains(k))
        }

        // Serialized representation must be exactly 64 bytes
        val filterBytes = filter.toByteArray()
        assertEquals(64, filterBytes.size)

        // Deserialized filter must preserve exact bitset state
        val reconstructed = DtnBloomFilter.fromByteArray(filterBytes)
        for (k in keys) {
            assertTrue("Reconstructed filter must contain $k", reconstructed.contains(k))
        }
    }

    @Test
    fun blindTokenGenerator_producesDeterministic16ByteToken() {
        val fakePublicKey = "4a5b6c7d8e9f00112233445566778899aabbccddeeff00112233445566778899"
        val day1 = 20350L
        val day2 = 20351L

        val tokenDay1A = BlindTokenGenerator.generateTokenFromHex(fakePublicKey, day1)
        val tokenDay1B = BlindTokenGenerator.generateTokenFromHex(fakePublicKey, day1)
        val tokenDay2 = BlindTokenGenerator.generateTokenFromHex(fakePublicKey, day2)

        assertEquals(16, tokenDay1A.size)
        assertEquals(16, tokenDay1B.size)
        assertEquals(16, tokenDay2.size)

        // Same public key + same day -> Identical blind token
        assertTrue(tokenDay1A.contentEquals(tokenDay1B))

        // Same public key + rotated day -> Completely different blind token (Anti-tracking)
        assertFalse(tokenDay1A.contentEquals(tokenDay2))
    }

    @Test
    fun dtnEnvelope_storeCarryForward_hopLimitDecrementsToZero() {
        var hop: Byte = 7
        val envId = ByteArray(16)
        val blindToken = ByteArray(16)
        val payload = ByteArray(20)

        var envelope = DtnEnvelope(envId, blindToken, hop, 1735689600L, payload)
        assertEquals(7, envelope.hopLimit.toInt())

        // Simulate 7 intermediate data mule hops
        while (envelope.hopLimit > 0) {
            envelope = envelope.copy(hopLimit = (envelope.hopLimit - 1).toByte())
        }

        assertEquals(0, envelope.hopLimit.toInt())
        assertTrue("Hop limit exhausted; node must drop forwarding", envelope.hopLimit <= 0)
    }
}
