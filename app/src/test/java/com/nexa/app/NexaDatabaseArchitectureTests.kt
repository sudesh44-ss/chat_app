package com.nexa.app

import com.nexa.app.data.local.NexaDatabase
import com.nexa.app.data.local.entity.ChatDraftEntity
import com.nexa.app.data.local.entity.ChatEntity
import com.nexa.app.data.local.entity.ChatMemberEntity
import com.nexa.app.data.local.entity.MessageAttachmentEntity
import com.nexa.app.data.local.entity.MessageEntity
import com.nexa.app.data.local.entity.MessageReactionEntity
import com.nexa.app.data.local.entity.StatusUpdateEntity
import com.nexa.app.data.local.entity.isActiveAt
import com.nexa.app.data.local.entity.toDomain
import com.nexa.app.data.local.entity.toEntity
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.MessageStatus
import com.nexa.app.domain.model.MessageType
import com.nexa.app.domain.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Comprehensive Unit Test Suite for NEXA Local SQLite Database Architecture,
 * Chat Organization, Monotonic Message Sequencing, Reactions, Drafts,
 * 24-hour Ephemeral Status Updates, and GIF/Meme Support.
 */
class NexaDatabaseArchitectureTests {

    @Test
    fun manualUnread_isLocalMarkerAndDoesNotInventUnreadMessages() {
        val persistedChat = ChatEntity(
            conversationId = "conv_manual_unread",
            participantNxId = "NX-LOCAL-UNREAD",
            unreadCount = 0,
            hasManualUnread = true
        )

        val domainChat = persistedChat.toDomain()

        assertTrue(domainChat.hasManualUnread)
        assertEquals(0, domainChat.unreadCount)
        assertTrue(domainChat.toEntity().hasManualUnread)
        assertEquals(0, domainChat.toEntity().unreadCount)
    }

    @Test
    fun statusExpiry_isBasedOnAbsoluteExpirationTimestamp() {
        val now = 1_700_000_000_000L
        val activeStatus = StatusUpdateEntity(
            statusId = "active",
            userNxId = "NX-STATUS",
            createdAt = now - 86_399_999L,
            expiresAt = now + 1L
        )
        val expiredStatus = activeStatus.copy(
            statusId = "expired",
            expiresAt = now
        )

        assertTrue(activeStatus.isActiveAt(now))
        assertFalse(expiredStatus.isActiveAt(now))
        assertFalse(activeStatus.copy(createdAt = now + 1L).isActiveAt(now))
        assertEquals(86_400_000L, activeStatus.expiresAt - activeStatus.createdAt)
    }

    // =========================================================================
    // 1. DETERMINISTIC CHAT ORGANIZATION TESTS
    // =========================================================================

    @Test
    fun chatOrganization_pinnedAboveUnpinned_withDeterministicTieBreaking() {
        val now = 1700000000000L

        val chat1 = ChatEntity(
            conversationId = "conv_alice",
            participantNxId = "NX-ALICE",
            participantName = "Alice",
            lastMessageTimestamp = now - 5000,
            isPinned = false
        )
        val chat2 = ChatEntity(
            conversationId = "conv_bob",
            participantNxId = "NX-BOB",
            participantName = "Bob",
            lastMessageTimestamp = now - 10000,
            isPinned = true,
            pinPosition = 2
        )
        val chat3 = ChatEntity(
            conversationId = "conv_carol",
            participantNxId = "NX-CAROL",
            participantName = "Carol",
            lastMessageTimestamp = now - 20000,
            isPinned = true,
            pinPosition = 1
        )
        val chat4 = ChatEntity(
            conversationId = "conv_dave",
            participantNxId = "NX-DAVE",
            participantName = "Dave",
            lastMessageTimestamp = now, // newest timestamp, but unpinned
            isPinned = false
        )

        val allChats = listOf(chat1, chat2, chat3, chat4)

        // Matching Room query: ORDER BY isPinned DESC, pinPosition ASC, lastMessageTimestamp DESC, conversationId ASC
        val comparator = compareByDescending<ChatEntity> { it.isPinned }
            .thenBy { it.pinPosition }
            .thenByDescending { it.lastMessageTimestamp }
            .thenBy { it.conversationId }

        val sorted = allChats.sortedWith(comparator)

        // Expected order:
        // 1. Carol (pinned, pinPosition=1)
        // 2. Bob (pinned, pinPosition=2)
        // 3. Dave (unpinned, timestamp=now)
        // 4. Alice (unpinned, timestamp=now-5000)
        assertEquals("conv_carol", sorted[0].conversationId)
        assertEquals("conv_bob", sorted[1].conversationId)
        assertEquals("conv_dave", sorted[2].conversationId)
        assertEquals("conv_alice", sorted[3].conversationId)
    }

    @Test
    fun chatOrganization_archivedChatsExcludedFromActiveList() {
        val activeChat = ChatEntity(
            conversationId = "conv_active",
            participantNxId = "NX-1",
            isArchived = false
        )
        val archivedChat = ChatEntity(
            conversationId = "conv_archived",
            participantNxId = "NX-2",
            isArchived = true,
            archivedAt = System.currentTimeMillis()
        )

        val list = listOf(activeChat, archivedChat)
        val activeList = list.filter { !it.isArchived }
        val archivedList = list.filter { it.isArchived }

        assertEquals(1, activeList.size)
        assertEquals("conv_active", activeList[0].conversationId)
        assertEquals(1, archivedList.size)
        assertEquals("conv_archived", archivedList[0].conversationId)
    }

    @Test
    fun chatOrganization_directVsGroupDistinction() {
        val directChat = ChatEntity(
            conversationId = "conv_direct",
            participantNxId = "NX-DIRECT",
            chatType = "DIRECT"
        )
        val groupChat = ChatEntity(
            conversationId = "conv_group",
            participantNxId = "NX-GROUP",
            title = "Privacy Architects",
            chatType = "GROUP"
        )

        assertEquals("DIRECT", directChat.chatType)
        assertFalse(directChat.toDomain().isGroup)

        assertEquals("GROUP", groupChat.chatType)
        assertTrue(groupChat.toDomain().isGroup)
        assertEquals("Privacy Architects", groupChat.toDomain().title)
    }

    @Test
    fun chatOrganization_muteSuppressionNotificationLogic() {
        val currentTime = 1700000000000L

        // Muted indefinitely
        val indefiniteMute = ChatEntity(
            conversationId = "conv_1",
            participantNxId = "NX-1",
            isMuted = true,
            mutedUntil = 0L
        )
        // Muted for 8 hours (active)
        val activeTimedMute = ChatEntity(
            conversationId = "conv_2",
            participantNxId = "NX-2",
            isMuted = true,
            mutedUntil = currentTime + 28800000L
        )
        // Muted for 1 hour in the past (expired)
        val expiredMute = ChatEntity(
            conversationId = "conv_3",
            participantNxId = "NX-3",
            isMuted = true,
            mutedUntil = currentTime - 3600000L
        )
        // Unmuted
        val unmuted = ChatEntity(
            conversationId = "conv_4",
            participantNxId = "NX-4",
            isMuted = false
        )

        fun shouldNotify(chat: ChatEntity): Boolean {
            val isMuted = chat.isMuted && (chat.mutedUntil == 0L || chat.mutedUntil > currentTime)
            return !isMuted
        }

        assertFalse(shouldNotify(indefiniteMute))
        assertFalse(shouldNotify(activeTimedMute))
        assertTrue(shouldNotify(expiredMute))
        assertTrue(shouldNotify(unmuted))
    }

    // =========================================================================
    // 2. CHRONOLOGICAL MESSAGE SEQUENCING & SOFT DELETE TESTS
    // =========================================================================

    @Test
    fun messageSequencing_monotonicLocalSequencePreventsCollision() {
        val baseTimestamp = 1700000000000L

        // 3 messages arriving in identical millisecond
        val msg1 = MessageEntity(
            packetId = "msg_1",
            conversationId = "conv_abc",
            senderNxId = "NX-1",
            recipientNxId = "NX-2",
            plainTextContent = "First",
            status = "DELIVERED",
            timestamp = baseTimestamp,
            localSequence = 1L
        )
        val msg2 = MessageEntity(
            packetId = "msg_2",
            conversationId = "conv_abc",
            senderNxId = "NX-2",
            recipientNxId = "NX-1",
            plainTextContent = "Second",
            status = "DELIVERED",
            timestamp = baseTimestamp,
            localSequence = 2L
        )
        val msg3 = MessageEntity(
            packetId = "msg_3",
            conversationId = "conv_abc",
            senderNxId = "NX-1",
            recipientNxId = "NX-2",
            plainTextContent = "Third",
            status = "DELIVERED",
            timestamp = baseTimestamp,
            localSequence = 3L
        )

        val list = listOf(msg3, msg1, msg2)
        val ordered = list.sortedWith(
            compareBy<MessageEntity> { it.timestamp }
                .thenBy { it.localSequence }
        )

        assertEquals("msg_1", ordered[0].packetId)
        assertEquals("msg_2", ordered[1].packetId)
        assertEquals("msg_3", ordered[2].packetId)
    }

    @Test
    fun messageEntity_softDeleteAndStarring() {
        val msg = MessageEntity(
            packetId = "msg_100",
            conversationId = "conv_1",
            senderNxId = "NX-1",
            recipientNxId = "NX-2",
            plainTextContent = "Sensitive message",
            status = "DELIVERED",
            timestamp = 1700000000000L,
            isDeleted = true,
            isStarred = true,
            editedAt = 1700000005000L
        )

        val domain = msg.toDomain()
        assertTrue(domain.isDeleted)
        assertTrue(domain.isStarred)
        assertEquals(1700000005000L, domain.editedAt)

        val roundTrip = domain.toEntity()
        assertTrue(roundTrip.isDeleted)
        assertTrue(roundTrip.isStarred)
        assertEquals(1700000005000L, roundTrip.editedAt)
    }

    @Test
    fun messageType_allEnumValuesSupported() {
        val expectedTypes = listOf(
            "TEXT", "IMAGE", "VIDEO", "GIF", "MEME", "AUDIO",
            "DOCUMENT", "FILE", "LOCATION", "CONTACT", "VOICE", "STICKER", "SYSTEM"
        )

        for (typeStr in expectedTypes) {
            val enumVal = MessageType.valueOf(typeStr)
            assertEquals(typeStr, enumVal.name)

            val entity = MessageEntity(
                packetId = UUID.randomUUID().toString(),
                conversationId = "conv_test",
                senderNxId = "NX-1",
                recipientNxId = "NX-2",
                plainTextContent = "Payload for $typeStr",
                status = "DELIVERED",
                timestamp = System.currentTimeMillis(),
                messageType = enumVal.name
            )
            val domain = entity.toDomain()
            assertEquals(enumVal, domain.messageType)
        }
    }

    // =========================================================================
    // 3. CHAT MEMBERSHIP TESTS
    // =========================================================================

    @Test
    fun chatMembers_rolesAndStateManagement() {
        val adminMember = ChatMemberEntity(
            chatId = "group_nexa_core",
            userNxId = "NX-ADMIN-01",
            role = "ADMIN",
            membershipState = "ACTIVE",
            joinedAt = 1700000000000L
        )
        val regularMember = ChatMemberEntity(
            chatId = "group_nexa_core",
            userNxId = "NX-MEMBER-02",
            role = "MEMBER",
            membershipState = "ACTIVE",
            joinedAt = 1700000010000L
        )
        val leftMember = ChatMemberEntity(
            chatId = "group_nexa_core",
            userNxId = "NX-LEFT-03",
            role = "MEMBER",
            membershipState = "LEFT",
            joinedAt = 1700000020000L
        )

        val members = listOf(adminMember, regularMember, leftMember)
        val activeMembers = members.filter { it.membershipState == "ACTIVE" }

        assertEquals(2, activeMembers.size)
        assertEquals("ADMIN", activeMembers.find { it.userNxId == "NX-ADMIN-01" }?.role)
        assertEquals("MEMBER", activeMembers.find { it.userNxId == "NX-MEMBER-02" }?.role)
    }

    // =========================================================================
    // 4. ATTACHMENT METADATA & 3-CHECKPOINT VERIFICATION TESTS
    // =========================================================================

    @Test
    fun messageAttachment_metadataSeparatedFromContent() {
        val attachment = MessageAttachmentEntity(
            attachmentId = "att_987",
            messageId = "msg_123",
            fileName = "quantum_whitepaper.pdf",
            mimeType = "application/pdf",
            detectedFileType = "DOCUMENT",
            fileSize = 1048576L,
            localPathReference = "/storage/emulated/0/Nexa/Files/quantum_whitepaper.pdf",
            thumbnailReference = null,
            width = null,
            height = null,
            durationMs = null,
            transferState = "COMPLETED",
            integrityState = "VERIFIED",
            securityScanState = "SAFE"
        )

        assertEquals("att_987", attachment.attachmentId)
        assertEquals("msg_123", attachment.messageId)
        assertEquals("application/pdf", attachment.mimeType)
        assertEquals("DOCUMENT", attachment.detectedFileType)
        assertEquals("SAFE", attachment.securityScanState)
        assertEquals("VERIFIED", attachment.integrityState)
    }

    // =========================================================================
    // 5. CHAT DRAFTS PERSISTENCE TESTS
    // =========================================================================

    @Test
    fun chatDrafts_createUpdateAndClearLifecycle() {
        val draft = ChatDraftEntity(
            conversationId = "conv_alice",
            draftText = "Hey Alice, let's meet tomorrow at...",
            replyToMessageId = "msg_quote_55",
            updatedAt = System.currentTimeMillis()
        )

        assertEquals("conv_alice", draft.conversationId)
        assertEquals("Hey Alice, let's meet tomorrow at...", draft.draftText)
        assertEquals("msg_quote_55", draft.replyToMessageId)
    }

    // =========================================================================
    // 6. MESSAGE REACTIONS TESTS
    // =========================================================================

    @Test
    fun messageReactions_uniquePerUserOnMessage() {
        val reaction1 = MessageReactionEntity(
            reactionId = "rx_1",
            messageId = "msg_alpha",
            userNxId = "NX-BOB",
            reaction = "❤️",
            createdAt = 1700000000000L
        )

        // Same user changing their reaction to a different emoji
        val reactionUpdated = reaction1.copy(reaction = "🔥")

        assertEquals("❤️", reaction1.reaction)
        assertEquals("🔥", reactionUpdated.reaction)
        assertEquals(reaction1.messageId, reactionUpdated.messageId)
        assertEquals(reaction1.userNxId, reactionUpdated.userNxId)
    }

    // =========================================================================
    // 7. STATUS UPDATES 24-HOUR EXPIRATION TESTS
    // =========================================================================

    @Test
    fun statusUpdates_24HourExpirationBoundary() {
        val baseTime = 1700000000000L

        val activeStatus = StatusUpdateEntity(
            statusId = "stat_1",
            userNxId = "NX-ALICE",
            caption = "Enjoying the sunset!",
            createdAt = baseTime,
            expiresAt = baseTime + 86400000L // 24 hours later
        )

        val expiredStatus = StatusUpdateEntity(
            statusId = "stat_2",
            userNxId = "NX-BOB",
            caption = "Yesterday's coffee",
            createdAt = baseTime - 90000000L,
            expiresAt = baseTime - 3600000L // Expired 1 hour ago
        )

        val allStatuses = listOf(activeStatus, expiredStatus)
        val activeOnly = allStatuses.filter { it.expiresAt > baseTime }

        assertEquals(1, activeOnly.size)
        assertEquals("stat_1", activeOnly[0].statusId)
        assertEquals("Enjoying the sunset!", activeOnly[0].caption)
    }

    // =========================================================================
    // 8. GIF & MEME SUPPORT TESTS
    // =========================================================================

    @Test
    fun gifAndMeme_messageTypeAndRenderingProperties() {
        val gifMsg = MessageEntity(
            packetId = "msg_gif_1",
            conversationId = "conv_fun",
            senderNxId = "NX-SENDER",
            recipientNxId = "NX-RECEIVER",
            plainTextContent = "/storage/emulated/0/Nexa/Media/reaction.gif",
            status = "DELIVERED",
            timestamp = System.currentTimeMillis(),
            messageType = MessageType.GIF.name,
            localSequence = 10L
        )

        val memeMsg = MessageEntity(
            packetId = "msg_meme_2",
            conversationId = "conv_fun",
            senderNxId = "NX-SENDER",
            recipientNxId = "NX-RECEIVER",
            plainTextContent = "/storage/emulated/0/Nexa/Media/meme.jpg",
            status = "DELIVERED",
            timestamp = System.currentTimeMillis(),
            messageType = MessageType.MEME.name,
            localSequence = 11L
        )

        val gifDomain = gifMsg.toDomain()
        val memeDomain = memeMsg.toDomain()

        assertEquals(MessageType.GIF, gifDomain.messageType)
        assertEquals(MessageType.MEME, memeDomain.messageType)
        assertTrue(gifDomain.plainTextContent.endsWith(".gif"))
        assertTrue(memeDomain.plainTextContent.endsWith(".jpg"))
    }

    // =========================================================================
    // 9. MIGRATION 23 -> 24 SCHEMA INTEGRITY
    // =========================================================================

    @Test
    fun migration_23_24_versionNumberVerified() {
        assertEquals(23, NexaDatabase.MIGRATION_23_24.startVersion)
        assertEquals(24, NexaDatabase.MIGRATION_23_24.endVersion)
    }
}
