package com.nexa.app

import com.nexa.app.core.demo.DemoDataSeeder
import com.nexa.app.data.local.dao.ChatDao
import com.nexa.app.data.local.dao.ContactDao
import com.nexa.app.data.local.dao.MessageDao
import com.nexa.app.data.local.entity.ChatEntity
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.MessageEntity
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.presentation.dashboard.components.BottomNavDestination
import com.nexa.app.presentation.dashboard.components.HomeDashboardTab
import com.nexa.app.presentation.dashboard.isGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoConversationsAndBottomNavUnitTests {

    @Test
    fun testInMemoryDemoConversations_containsExpectedTwoConversations() {
        val demoList = DemoDataSeeder.getInMemoryDemoConversations()
        assertEquals(2, demoList.size)

        val personal = demoList.first { !it.isGroup }
        assertEquals(DemoDataSeeder.DEMO_PERSONAL_CONVERSATION_ID, personal.conversationId)
        assertEquals("Demo Contact", personal.participantName)
        assertEquals("NX-DEMO-CONTACT", personal.participantNxId)
        assertEquals("Hey! Welcome to NEXA.", personal.lastMessageText)
        assertFalse(personal.isGroup)

        val group = demoList.first { it.isGroup }
        assertEquals(DemoDataSeeder.DEMO_GROUP_CONVERSATION_ID, group.conversationId)
        assertEquals("Demo Group", group.participantName)
        assertEquals("NX-DEMO-GROUP", group.participantNxId)
        assertEquals("Welcome everyone!", group.lastMessageText)
        assertTrue(group.isGroup)
    }

    @Test
    fun testTabRouting_routesDemoConversationsToCorrectTabs() {
        val demoList = DemoDataSeeder.getInMemoryDemoConversations()

        val chatsTabList = demoList.filter { !it.isGroup }
        assertEquals(1, chatsTabList.size)
        assertEquals("Demo Contact", chatsTabList[0].participantName)

        val groupsTabList = demoList.filter { it.isGroup }
        assertEquals(1, groupsTabList.size)
        assertEquals("Demo Group", groupsTabList[0].participantName)
    }

    @Test
    fun testDemoDataSeeder_cleansUpLegacyAlexAndSeedsNewContact() = runTest {
        val fakeChatDao = TestFakeChatDao()
        val fakeMessageDao = TestFakeMessageDao()
        val fakeContactDao = TestFakeContactDao()

        // Insert legacy chat from previous build
        fakeChatDao.upsertChat(
            ChatEntity(
                conversationId = "demo_personal_alex",
                participantNxId = "NX-DEMO-ALEX",
                participantName = "Alex Demo",
                lastMessageText = "Legacy message",
                lastMessageTimestamp = 500L
            )
        )
        assertNotNull(fakeChatDao.getChatById("demo_personal_alex"))

        // Run seed
        DemoDataSeeder.seedDemoConversations(fakeChatDao, fakeMessageDao, fakeContactDao)

        // Verify legacy was cleaned up
        assertNull(fakeChatDao.getChatById("demo_personal_alex"))

        // Verify new "Demo Contact" and "Demo Group" were inserted
        val personalChat = fakeChatDao.getChatById(DemoDataSeeder.DEMO_PERSONAL_CONVERSATION_ID)
        assertNotNull(personalChat)
        assertEquals("Demo Contact", personalChat?.participantName)
        assertEquals("Hey! Welcome to NEXA.", personalChat?.lastMessageText)

        val groupChat = fakeChatDao.getChatById(DemoDataSeeder.DEMO_GROUP_CONVERSATION_ID)
        assertNotNull(groupChat)
        assertEquals("Demo Group", groupChat?.participantName)
        assertEquals("Welcome everyone!", groupChat?.lastMessageText)

        // Verify contacts and messages
        assertNotNull(fakeContactDao.getContact("NX-DEMO-CONTACT"))
        assertTrue(fakeMessageDao.messages.any { it.plainTextContent == "Hey! Welcome to NEXA." })
        assertTrue(fakeMessageDao.messages.any { it.plainTextContent == "Welcome everyone!" })
    }

    @Test
    fun testDemoDataSeeder_isIdempotentOnRepeatedCalls() = runTest {
        val fakeChatDao = TestFakeChatDao()
        val fakeMessageDao = TestFakeMessageDao()
        val fakeContactDao = TestFakeContactDao()

        DemoDataSeeder.seedDemoConversations(fakeChatDao, fakeMessageDao, fakeContactDao)
        val initialChatCount = fakeChatDao.chats.size
        val initialMsgCount = fakeMessageDao.messages.size

        // Second seed call
        DemoDataSeeder.seedDemoConversations(fakeChatDao, fakeMessageDao, fakeContactDao)
        assertEquals(initialChatCount, fakeChatDao.chats.size)
        assertEquals(initialMsgCount, fakeMessageDao.messages.size)
    }

    @Test
    fun testBottomNavDestinations() {
        assertEquals("HOME", BottomNavDestination.HOME.name)
        assertEquals("PROFILE", BottomNavDestination.PROFILE.name)
    }

    // ==========================================
    // Test Fakes
    // ==========================================

    private class TestFakeChatDao : ChatDao {
        val chats = mutableMapOf<String, ChatEntity>()

        override suspend fun upsertChat(chat: ChatEntity): Long {
            chats[chat.conversationId] = chat
            return 1L
        }

        override suspend fun getChatById(conversationId: String): ChatEntity? = chats[conversationId]

        override suspend fun getChatByParticipant(participantNxId: String): ChatEntity? {
            return chats.values.firstOrNull { it.participantNxId == participantNxId }
        }

        override fun getChatsFlow(): Flow<List<ChatEntity>> = flowOf(chats.values.toList())

        override suspend fun deleteChat(conversationId: String): Int {
            chats.remove(conversationId)
            return 1
        }

        override suspend fun resetUnreadCount(conversationId: String): Int = 0
        override suspend fun markConversationUnread(conversationId: String, updatedAt: Long): Int {
            val chat = chats[conversationId] ?: return 0
            chats[conversationId] = chat.copy(hasManualUnread = true, updatedAt = updatedAt)
            return 1
        }

        override suspend fun updateParticipantName(nxId: String, newName: String): Int = 0

        override suspend fun clearLastMessageText(conversationId: String): Int = 1
        override fun getArchivedChatsFlow(): Flow<List<ChatEntity>> = flowOf(chats.values.filter { it.isArchived })
        override fun getChatsByTypeFlow(chatType: String): Flow<List<ChatEntity>> =
            flowOf(chats.values.filter { !it.isArchived && it.chatType == chatType })
        override suspend fun updatePinStatus(conversationId: String, isPinned: Boolean, pinPosition: Int, pinnedAt: Long?, updatedAt: Long): Int = 1
        override suspend fun updateArchiveStatus(conversationId: String, isArchived: Boolean, archivedAt: Long?, updatedAt: Long): Int = 1
        override suspend fun updateMuteStatus(conversationId: String, isMuted: Boolean, mutedUntil: Long, updatedAt: Long): Int = 1
        override suspend fun updateLastMessage(conversationId: String, text: String, timestamp: Long, lastMessageId: String?, lastSenderNxId: String?): Int = 1
        override suspend fun incrementUnread(conversationId: String): Int = 1
        override suspend fun countActiveChats(): Int = chats.values.count { !it.isArchived }
    }

    private class TestFakeMessageDao : MessageDao {
        val messages = mutableListOf<MessageEntity>()

        override fun getMessagesFlow(conversationId: String): Flow<List<MessageEntity>> =
            flowOf(messages.filter { it.conversationId == conversationId })
        override suspend fun getMessagesPaged(conversationId: String, limit: Int, offset: Int): List<MessageEntity> =
            messages.filter { it.conversationId == conversationId && !it.isDeleted }
                .sortedWith(compareByDescending<MessageEntity> { it.timestamp }.thenByDescending { it.localSequence })
                .drop(offset).take(limit)
        override suspend fun getNextLocalSequence(conversationId: String): Long =
            (messages.filter { it.conversationId == conversationId }.maxOfOrNull { it.localSequence } ?: 0L) + 1L
        override suspend fun softDeleteMessage(packetId: String): Int = 1
        override suspend fun updateStarredStatus(packetId: String, isStarred: Boolean): Int = 1
        override suspend fun insertMessage(message: MessageEntity): Long {
            messages.add(message)
            return 1L
        }
        override suspend fun insertMessageIfNotExists(message: MessageEntity): Long {
            if (messages.none { it.packetId == message.packetId }) {
                messages.add(message)
            }
            return 1L
        }
        override suspend fun updateMessageStatus(packetId: String, status: String): Int = 1
        override suspend fun getPendingOutgoingMessages(): List<MessageEntity> = emptyList()
        override suspend fun markMessagesAsRead(conversationId: String): Int = 1
        override suspend fun getMessageByPacketId(packetId: String): MessageEntity? =
            messages.firstOrNull { it.packetId == packetId }
        override suspend fun getLatestMessage(conversationId: String): MessageEntity? =
            messages.filter { it.conversationId == conversationId }.lastOrNull()
        override fun getPinnedMessagesFlow(conversationId: String, currentTimeMillis: Long): Flow<List<MessageEntity>> =
            flowOf(emptyList())
        override suspend fun getPinnedMessages(conversationId: String, currentTimeMillis: Long): List<MessageEntity> = emptyList()
        override suspend fun getPinnedMessagesCount(conversationId: String, currentTimeMillis: Long): Int = 0
        override suspend fun updatePinStatus(packetId: String, isPinned: Boolean, pinnedExpiresAt: Long?, pinnedAt: Long?): Int = 1
        override suspend fun unpinMessage(packetId: String): Int = 1
        override suspend fun clearExpiredPins(conversationId: String, currentTimeMillis: Long): Int = 0
        override suspend fun deleteMessagesForConversation(conversationId: String): Int = 1
        override suspend fun getMessageIdsAfterTimestamp(conversationId: String, afterTimestamp: Long, limit: Int): List<String> =
            messages.filter { it.conversationId == conversationId && it.timestamp > afterTimestamp }.take(limit).map { it.packetId }
        override suspend fun getMessagesAfterTimestamp(conversationId: String, afterTimestamp: Long, limit: Int): List<MessageEntity> =
            messages.filter { it.conversationId == conversationId && it.timestamp > afterTimestamp }.take(limit)
        override suspend fun searchMessagesInConversation(conversationId: String, query: String): List<MessageEntity> =
            messages.filter { it.conversationId == conversationId && it.plainTextContent.contains(query, ignoreCase = true) }
        override suspend fun getFirstMessageOnDate(conversationId: String, startOfDay: Long, endOfDay: Long): MessageEntity? =
            messages.filter { it.conversationId == conversationId && it.timestamp in startOfDay until endOfDay }.minByOrNull { it.timestamp }
        override suspend fun getMessagesOnDate(conversationId: String, startOfDay: Long, endOfDay: Long): List<MessageEntity> =
            messages.filter { it.conversationId == conversationId && it.timestamp in startOfDay until endOfDay }
        override suspend fun countMessagesOnDate(conversationId: String, startOfDay: Long, endOfDay: Long): Int =
            messages.count { it.conversationId == conversationId && it.timestamp in startOfDay until endOfDay }
        override suspend fun countMessagesBeforeTimestamp(conversationId: String, timestamp: Long): Int =
            messages.count { it.conversationId == conversationId && it.timestamp < timestamp }
    }

    private class TestFakeContactDao : ContactDao {
        val contacts = mutableMapOf<String, ContactEntity>()

        override suspend fun upsertContact(contact: ContactEntity): Long {
            contacts[contact.nxId] = contact
            return 1L
        }
        override fun getContactsByStatus(status: com.nexa.app.data.local.entity.ContactStatus): Flow<List<ContactEntity>> =
            flowOf(contacts.values.filter { it.status == status })
        override suspend fun getContact(nxId: String): ContactEntity? = contacts[nxId]
        override suspend fun updateStatus(nxId: String, newStatus: com.nexa.app.data.local.entity.ContactStatus): Int = 1
        override suspend fun updateStatusMuted(nxId: String, isMuted: Boolean): Int {
            val contact = contacts[nxId] ?: return 0
            contacts[nxId] = contact.copy(isStatusMuted = isMuted)
            return 1
        }
        override suspend fun deleteContact(nxId: String): Int = 1
        override fun getPendingReceivedCount(): Flow<Int> = flowOf(0)
        override fun getAllContacts(): Flow<List<ContactEntity>> = flowOf(contacts.values.toList())
        override suspend fun updateNickname(nxId: String, nickname: String?): Int = 1
        override suspend fun updateDeviceContactName(nxId: String, deviceContactName: String?): Int = 1
        override fun searchContacts(query: String): Flow<List<ContactEntity>> = flowOf(emptyList())
        override suspend fun updatePeerPhoneSharing(nxId: String, isShared: Boolean, phone: String?): Int = 1
        override suspend fun updateAvatarUrl(nxId: String, avatarUrl: String?): Int = 1
        override suspend fun updateDeviceContactPhotoUri(nxId: String, photoUri: String?): Int = 1
    }
}
