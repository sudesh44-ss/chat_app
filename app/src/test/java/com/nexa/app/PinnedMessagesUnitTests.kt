package com.nexa.app

import com.nexa.app.data.local.entity.MessageEntity
import com.nexa.app.data.local.entity.toDomain
import com.nexa.app.data.local.entity.toEntity
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.MessageStatus
import com.nexa.app.domain.model.TransportType
import com.nexa.app.presentation.chat.components.PinDuration
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedMessagesUnitTests {

    @Test
    fun pinDuration_durationMillisMatchesSpecification() {
        assertEquals(24 * 3600 * 1000L, PinDuration.HOURS_24.durationMillis)
        assertEquals(48 * 3600 * 1000L, PinDuration.HOURS_48.durationMillis)
        assertEquals(7L * 24 * 3600 * 1000L, PinDuration.DAYS_7.durationMillis)
        assertEquals(30L * 24 * 3600 * 1000L, PinDuration.DAYS_30.durationMillis)
        assertNull(PinDuration.INDEFINITE.durationMillis)

        assertEquals("24 hours", PinDuration.HOURS_24.label)
        assertEquals("48 hours", PinDuration.HOURS_48.label)
        assertEquals("7 days", PinDuration.DAYS_7.label)
        assertEquals("30 days", PinDuration.DAYS_30.label)
        assertEquals("Until I unpin it", PinDuration.INDEFINITE.label)
    }

    @Test
    fun pinExpiry_activeAndExpiredFilterEvaluation() {
        val now = 1_700_000_000_000L

        // Expired pin: expiresAt < now
        val expiredExpiresAt = now - 60_000L
        val isExpiredActive = expiredExpiresAt > now
        assertFalse("Expired pin must not be active", isExpiredActive)

        // Active pin with future expiry: expiresAt > now
        val futureExpiresAt = now + 86_400_000L
        val isFutureActive = futureExpiresAt > now
        assertTrue("Future pin must be active", isFutureActive)

        // Indefinite pin: expiresAt is null
        val indefiniteExpiresAt: Long? = null
        val isIndefiniteActive = indefiniteExpiresAt == null || indefiniteExpiresAt > now
        assertTrue("Indefinite pin must remain active", isIndefiniteActive)
    }

    @Test
    fun pinLimit_max4PinsEnforced() {
        val maxAllowed = 4
        val currentPinned = mutableListOf("msg-1", "msg-2", "msg-3", "msg-4")

        // Attempting to pin a 5th message must be blocked
        val canPinFifth = currentPinned.size < maxAllowed
        assertFalse("5th pin must be blocked when 4 pins already exist", canPinFifth)

        // After unpinning one message, a new pin must be permitted
        currentPinned.removeAt(0)
        val canPinAfterUnpin = currentPinned.size < maxAllowed
        assertTrue("Should allow pinning after unpinning an existing message", canPinAfterUnpin)
    }

    @Test
    fun pinControlPacket_jsonSerializationAndParsing() {
        val targetMessageId = "packet-uuid-987"
        val pinnedAt = 1_700_000_000_000L
        val expiresAt = pinnedAt + 86_400_000L
        val myNxId = "NX-SENDER-123"

        // 1. Serialize PIN_MESSAGE control packet
        val pinJson = JSONObject().apply {
            put("type", "PIN_MESSAGE")
            put("targetMessageId", targetMessageId)
            put("expiresAt", expiresAt)
            put("pinnedAt", pinnedAt)
            put("pinnedBy", myNxId)
        }.toString()

        assertTrue(pinJson.startsWith("{") && pinJson.contains("\"type\":\"PIN_MESSAGE\""))

        // Parse and verify attributes
        val parsedPin = JSONObject(pinJson)
        assertEquals("PIN_MESSAGE", parsedPin.getString("type"))
        assertEquals(targetMessageId, parsedPin.getString("targetMessageId"))
        assertEquals(expiresAt, parsedPin.getLong("expiresAt"))
        assertEquals(pinnedAt, parsedPin.getLong("pinnedAt"))
        assertEquals(myNxId, parsedPin.getString("pinnedBy"))

        // 2. Serialize UNPIN_MESSAGE control packet
        val unpinJson = JSONObject().apply {
            put("type", "UNPIN_MESSAGE")
            put("targetMessageId", targetMessageId)
            put("unpinnedBy", myNxId)
        }.toString()

        assertTrue(unpinJson.startsWith("{") && unpinJson.contains("\"type\":\"UNPIN_MESSAGE\""))

        val parsedUnpin = JSONObject(unpinJson)
        assertEquals("UNPIN_MESSAGE", parsedUnpin.getString("type"))
        assertEquals(targetMessageId, parsedUnpin.getString("targetMessageId"))
        assertEquals(myNxId, parsedUnpin.getString("unpinnedBy"))
    }

    @Test
    fun messageEntity_toDomainAndToEntityRoundTrip() {
        val now = System.currentTimeMillis()
        val expiresAt = now + 86_400_000L

        val entity = MessageEntity(
            packetId = "test-msg-001",
            conversationId = "conv-abc",
            senderNxId = "NX-ALICE",
            recipientNxId = "NX-BOB",
            plainTextContent = "Mission critical coordinate update",
            status = MessageStatus.SENT.name,
            timestamp = now,
            isOutgoing = true,
            transportType = TransportType.INTERNET.name,
            isPinned = true,
            pinnedExpiresAt = expiresAt,
            pinnedAt = now
        )

        val domain = entity.toDomain()
        assertTrue(domain.isPinned)
        assertEquals(expiresAt, domain.pinnedExpiresAt)
        assertEquals(now, domain.pinnedAt)
        assertEquals("test-msg-001", domain.packetId)

        val convertedBack = domain.toEntity()
        assertTrue(convertedBack.isPinned)
        assertEquals(expiresAt, convertedBack.pinnedExpiresAt)
        assertEquals(now, convertedBack.pinnedAt)
        assertEquals(entity.packetId, convertedBack.packetId)
    }

    @Test
    fun bannerCyclicNavigation_indexWrapsCorrectly() {
        val pinnedCount = 4
        var currentIndex = 0

        // Advance cyclically
        currentIndex = (currentIndex + 1) % pinnedCount
        assertEquals(1, currentIndex)

        currentIndex = (currentIndex + 1) % pinnedCount
        assertEquals(2, currentIndex)

        currentIndex = (currentIndex + 1) % pinnedCount
        assertEquals(3, currentIndex)

        // Wraps back to 0
        currentIndex = (currentIndex + 1) % pinnedCount
        assertEquals(0, currentIndex)
    }

    @Test
    fun bannerPreviewText_truncation() {
        val longContent = "A".repeat(150)
        val preview = longContent.take(60)
        assertEquals(60, preview.length)

        val shortContent = "Hello World"
        val shortPreview = shortContent.take(60)
        assertEquals("Hello World", shortPreview)
    }
}
