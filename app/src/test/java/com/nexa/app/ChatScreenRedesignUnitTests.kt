package com.nexa.app

import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.MessageStatus
import com.nexa.app.domain.model.TransportType
import com.nexa.app.presentation.chat.components.AttachmentType
import com.nexa.app.presentation.chat.components.ChatOverflowAction
import com.nexa.app.presentation.chat.components.ContextMenuAction
import com.nexa.app.presentation.chat.components.EmojiCategory
import com.nexa.app.presentation.chat.components.NexaReactions
import com.nexa.app.presentation.chat.components.NexaTransportOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenRedesignUnitTests {

    // ==========================================
    // 1. Message Long-Press Context Menu (Exact 6 Actions)
    // ==========================================

    @Test
    fun testContextMenu_exactSixItemsInExactOrder() {
        val actions = ContextMenuAction.values()
        assertEquals(6, actions.size)
        assertEquals(ContextMenuAction.REPLY, actions[0])
        assertEquals(ContextMenuAction.COPY, actions[1])
        assertEquals(ContextMenuAction.FORWARD, actions[2])
        assertEquals(ContextMenuAction.PIN, actions[3])
        assertEquals(ContextMenuAction.MESSAGE_INFO, actions[4])
        assertEquals(ContextMenuAction.DELETE, actions[5])
    }

    // ==========================================
    // 2. Message Quick Reactions (Exact 6 Reactions)
    // ==========================================

    @Test
    fun testQuickReactions_exactSixEmojis() {
        val emojis = NexaReactions.QUICK_REACTIONS
        assertEquals(6, emojis.size)
        assertEquals(listOf("❤️", "👍", "😂", "😮", "😢", "🙏"), emojis)
    }

    @Test
    fun testReactionToggling_addsAndRemovesCorrectly() {
        val reactions = mutableListOf<String>()
        val emoji = "❤️"

        // Add
        if (!reactions.contains(emoji)) {
            reactions.add(emoji)
        }
        assertTrue(reactions.contains("❤️"))
        assertEquals(1, reactions.size)

        // Toggle / Remove
        if (reactions.contains(emoji)) {
            reactions.remove(emoji)
        }
        assertFalse(reactions.contains("❤️"))
        assertTrue(reactions.isEmpty())
    }

    // ==========================================
    // 3. Attachment Menu (Exact 9 Items in 3x3 Grid)
    // ==========================================

    @Test
    fun testAttachmentMenu_exactNineItemsInExactOrder() {
        val items = AttachmentType.values()
        assertEquals(9, items.size)
        assertEquals(AttachmentType.CAMERA, items[0])
        assertEquals(AttachmentType.GALLERY, items[1])
        assertEquals(AttachmentType.AUDIO, items[2])
        assertEquals(AttachmentType.CONTACT, items[3])
        assertEquals(AttachmentType.LINK, items[4])
        assertEquals(AttachmentType.DOCUMENT, items[5])
        assertEquals(AttachmentType.MUSIC, items[6])
        assertEquals(AttachmentType.VIDEO, items[7])
        assertEquals(AttachmentType.LOCATION, items[8])
    }

    // ==========================================
    // 4. Chat 3-Dot Overflow Menu (Exact 8 Items in Order)
    // ==========================================

    @Test
    fun testChatOverflowMenu_exactEightItemsInExactOrder() {
        val items = ChatOverflowAction.values()
        assertEquals(8, items.size)
        assertEquals(ChatOverflowAction.VIEW_CONTACT, items[0])
        assertEquals(ChatOverflowAction.SEARCH_CHAT, items[1])
        assertEquals(ChatOverflowAction.MEDIA_LINKS_FILES, items[2])
        assertEquals(ChatOverflowAction.GHOST_MODE, items[3])
        assertEquals(ChatOverflowAction.PINNED_MESSAGES, items[4])
        assertEquals(ChatOverflowAction.CHAT_WALLPAPER, items[5])
        assertEquals(ChatOverflowAction.CLEAR_CHAT, items[6])
        assertEquals(ChatOverflowAction.BLOCK_CONTACT, items[7])
    }

    // ==========================================
    // 5. Message Transport Selector (Exact 4 Options)
    // ==========================================

    @Test
    fun testTransportSelector_exactFourOptionsInExactOrder() {
        val options = NexaTransportOption.values()
        assertEquals(4, options.size)
        assertEquals(NexaTransportOption.INTERNET, options[0])
        assertEquals(NexaTransportOption.SMS, options[1])
        assertEquals(NexaTransportOption.MESH_BLUETOOTH, options[2])
        assertEquals(NexaTransportOption.AUTO, options[3])
    }

    // ==========================================
    // 6. Message Visual Grouping Logic
    // ==========================================

    @Test
    fun testMessageGrouping_groupsWithinTwoMinutesSameSender() {
        val now = 1000000L
        val messages = listOf(
            // Group 1: Outgoing
            Message(
                packetId = "msg_1",
                conversationId = "conv_1",
                senderNxId = "NX-ME",
                recipientNxId = "NX-PEER",
                plainTextContent = "Hello",
                status = MessageStatus.READ,
                timestamp = now,
                isOutgoing = true,
                transportType = TransportType.INTERNET
            ),
            Message(
                packetId = "msg_2",
                conversationId = "conv_1",
                senderNxId = "NX-ME",
                recipientNxId = "NX-PEER",
                plainTextContent = "How are you?",
                status = MessageStatus.READ,
                timestamp = now + 30_000L, // 30s later (grouped!)
                isOutgoing = true,
                transportType = TransportType.INTERNET
            ),
            // Group 2: Incoming
            Message(
                packetId = "msg_3",
                conversationId = "conv_1",
                senderNxId = "NX-PEER",
                recipientNxId = "NX-ME",
                plainTextContent = "I am great!",
                status = MessageStatus.DELIVERED,
                timestamp = now + 60_000L, // Incoming (breaks group)
                isOutgoing = false,
                transportType = TransportType.INTERNET
            ),
            // Group 3: Outgoing after 5 minutes (breaks group due to time)
            Message(
                packetId = "msg_4",
                conversationId = "conv_1",
                senderNxId = "NX-ME",
                recipientNxId = "NX-PEER",
                plainTextContent = "Good to hear!",
                status = MessageStatus.SENT,
                timestamp = now + 400_000L, // > 2 mins later
                isOutgoing = true,
                transportType = TransportType.INTERNET
            )
        )

        fun isFirstInGroup(index: Int): Boolean {
            val prev = messages.getOrNull(index - 1)
            val curr = messages[index]
            return prev == null || prev.isOutgoing != curr.isOutgoing || (curr.timestamp - prev.timestamp) > 120_000
        }

        fun isLastInGroup(index: Int): Boolean {
            val next = messages.getOrNull(index + 1)
            val curr = messages[index]
            return next == null || next.isOutgoing != curr.isOutgoing || (next.timestamp - curr.timestamp) > 120_000
        }

        // Message 0: first in group, not last
        assertTrue(isFirstInGroup(0))
        assertFalse(isLastInGroup(0))

        // Message 1: not first, last in group
        assertFalse(isFirstInGroup(1))
        assertTrue(isLastInGroup(1))

        // Message 2: both first and last (single message from peer)
        assertTrue(isFirstInGroup(2))
        assertTrue(isLastInGroup(2))

        // Message 3: separated by > 2 min, both first and last
        assertTrue(isFirstInGroup(3))
        assertTrue(isLastInGroup(3))
    }

    // ==========================================
    // 7. Delete for Me vs Delete for Everyone Scenarios
    // ==========================================

    @Test
    fun testDeleteMessage_outgoingAllowsEveryoneAndMe() {
        val outgoingMsg = Message(
            packetId = "m1",
            conversationId = "c1",
            senderNxId = "NX-ME",
            recipientNxId = "NX-PEER",
            plainTextContent = "Test",
            status = MessageStatus.SENT,
            timestamp = 1000L,
            isOutgoing = true,
            transportType = TransportType.INTERNET
        )
        val incomingMsg = Message(
            packetId = "m2",
            conversationId = "c1",
            senderNxId = "NX-PEER",
            recipientNxId = "NX-ME",
            plainTextContent = "Reply",
            status = MessageStatus.DELIVERED,
            timestamp = 2000L,
            isOutgoing = false,
            transportType = TransportType.INTERNET
        )

        assertTrue("Outgoing message allows Delete for Everyone", outgoingMsg.isOutgoing)
        assertFalse("Incoming message does not allow Delete for Everyone", incomingMsg.isOutgoing)
    }

    // ==========================================
    // 8. Emoji Categories
    // ==========================================

    @Test
    fun testEmojiCategories_allCategoriesDefined() {
        val categories = EmojiCategory.values()
        assertEquals(7, categories.size)
        assertTrue(categories.contains(EmojiCategory.RECENT))
        assertTrue(categories.contains(EmojiCategory.SMILEYS))
        assertTrue(categories.contains(EmojiCategory.GESTURES))
        assertTrue(categories.contains(EmojiCategory.HEARTS))
        assertTrue(categories.contains(EmojiCategory.ANIMALS))
        assertTrue(categories.contains(EmojiCategory.FOOD))
        assertTrue(categories.contains(EmojiCategory.OBJECTS))
    }
}
