package com.nexa.app.core.demo

import android.util.Log
import com.nexa.app.core.config.AppLaunchConfig
import com.nexa.app.data.local.NexaDatabase
import com.nexa.app.data.local.entity.ChatEntity
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import com.nexa.app.data.local.entity.MessageEntity
import com.nexa.app.domain.model.MessageStatus
import com.nexa.app.domain.model.TransportType

/**
 * Isolated, idempotent local development-only data seeder for NEXA.
 *
 * Populates:
 * 1. Personal demo conversation ("Demo Contact", subtitle "Personal conversation • Testing", snippet "Hey! Welcome to NEXA.").
 * 2. Group demo conversation ("Demo Group", subtitle "Group conversation • Testing", snippet "Welcome everyone!").
 *
 * STRICT INVARIANTS:
 * - Active during development / testing or when [AppLaunchConfig.isBypassAuthToHomeScreenEnabled] is true.
 * - Entirely offline: zero cloud upload, zero network calls, zero sync to Supabase.
 * - Idempotent: checks for existing records before insertion.
 */
object DemoDataSeeder {

    private const val TAG = "DemoDataSeeder"

    const val DEMO_PERSONAL_CONVERSATION_ID = "demo_personal_contact"
    const val DEMO_PERSONAL_PARTICIPANT_NX_ID = "NX-DEMO-CONTACT"
    const val DEMO_PERSONAL_PARTICIPANT_NAME = "Demo Contact"

    const val DEMO_GROUP_CONVERSATION_ID = "demo_group_testing"
    const val DEMO_GROUP_PARTICIPANT_NX_ID = "NX-DEMO-GROUP"
    const val DEMO_GROUP_PARTICIPANT_NAME = "Demo Group"

    const val DEMO_MEMBER_SAM_NX_ID = "NX-DEMO-SAM"
    const val DEMO_MEMBER_PRIYA_NX_ID = "NX-DEMO-PRIYA"

    fun getInMemoryDemoConversations(): List<com.nexa.app.domain.model.ChatConversation> {
        val now = System.currentTimeMillis()
        return listOf(
            com.nexa.app.domain.model.ChatConversation(
                conversationId = DEMO_PERSONAL_CONVERSATION_ID,
                participantNxId = DEMO_PERSONAL_PARTICIPANT_NX_ID,
                participantName = DEMO_PERSONAL_PARTICIPANT_NAME,
                lastMessageText = "Hey! Welcome to NEXA.",
                lastMessageTimestamp = now - 60_000,
                unreadCount = 0
            ),
            com.nexa.app.domain.model.ChatConversation(
                conversationId = DEMO_GROUP_CONVERSATION_ID,
                participantNxId = DEMO_GROUP_PARTICIPANT_NX_ID,
                participantName = DEMO_GROUP_PARTICIPANT_NAME,
                lastMessageText = "Welcome everyone!",
                lastMessageTimestamp = now - 30_000,
                unreadCount = 0
            )
        )
    }

    suspend fun seedDemoConversations(database: NexaDatabase) {
        seedDemoConversations(
            chatDao = database.chatDao(),
            messageDao = database.messageDao(),
            contactDao = database.contactDao(),
            fileTransferDao = database.fileTransferDao()
        )
    }

    suspend fun seedDemoConversations(
        chatDao: com.nexa.app.data.local.dao.ChatDao,
        messageDao: com.nexa.app.data.local.dao.MessageDao,
        contactDao: com.nexa.app.data.local.dao.ContactDao,
        fileTransferDao: com.nexa.app.data.local.dao.FileTransferDao? = null
    ) {
        if (!com.nexa.app.BuildConfig.DEBUG && !AppLaunchConfig.isBypassAuthToHomeScreenEnabled) {
            Log.d(TAG, "Neither debug nor bypass mode active; skipping demo data seeding")
            return
        }

        try {
            val now = System.currentTimeMillis()

            // 0. Clean up legacy obsolete demo chat if present
            try {
                val legacyAlex = chatDao.getChatById("demo_personal_alex")
                if (legacyAlex != null) {
                    chatDao.deleteChat("demo_personal_alex")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Legacy demo cleanup ignored: ${e.message}")
            }

            // 1. Seed Contacts (Demo Contact, Sam, Priya)
            val demoContacts = listOf(
                ContactEntity(
                    nxId = DEMO_PERSONAL_PARTICIPANT_NX_ID,
                    displayName = DEMO_PERSONAL_PARTICIPANT_NAME,
                    publicKeyHex = "00".repeat(32),
                    customNickname = "Demo",
                    status = ContactStatus.MUTUAL_FRIEND,
                    createdAtTimestamp = now - 3600_000
                ),
                ContactEntity(
                    nxId = DEMO_MEMBER_SAM_NX_ID,
                    displayName = "Sam",
                    publicKeyHex = "00".repeat(32),
                    customNickname = "Sam",
                    status = ContactStatus.MUTUAL_FRIEND,
                    createdAtTimestamp = now - 3600_000
                ),
                ContactEntity(
                    nxId = DEMO_MEMBER_PRIYA_NX_ID,
                    displayName = "Priya",
                    publicKeyHex = "00".repeat(32),
                    customNickname = "Priya",
                    status = ContactStatus.MUTUAL_FRIEND,
                    createdAtTimestamp = now - 3600_000
                )
            )

            for (contact in demoContacts) {
                if (contactDao.getContact(contact.nxId) == null) {
                    contactDao.upsertContact(contact)
                }
            }

            // 2. Seed Personal Demo Conversation ("Demo Contact")
            val existingPersonalChat = chatDao.getChatById(DEMO_PERSONAL_CONVERSATION_ID)
            if (existingPersonalChat == null || existingPersonalChat.participantName != DEMO_PERSONAL_PARTICIPANT_NAME) {
                val personalChat = ChatEntity(
                    conversationId = DEMO_PERSONAL_CONVERSATION_ID,
                    participantNxId = DEMO_PERSONAL_PARTICIPANT_NX_ID,
                    participantName = DEMO_PERSONAL_PARTICIPANT_NAME,
                    lastMessageText = "Hey! Welcome to NEXA.",
                    lastMessageTimestamp = now - 60_000,
                    unreadCount = 0
                )
                chatDao.upsertChat(personalChat)

                val personalMessages = listOf(
                    MessageEntity(
                        packetId = "demo_contact_msg_1",
                        conversationId = DEMO_PERSONAL_CONVERSATION_ID,
                        senderNxId = DEMO_PERSONAL_PARTICIPANT_NX_ID,
                        recipientNxId = "NX-ANONYMOUS",
                        plainTextContent = "This is a local end-to-end encrypted demo chat.",
                        status = MessageStatus.READ.name,
                        timestamp = now - 180_000,
                        isOutgoing = false,
                        transportType = TransportType.INTERNET.name
                    ),
                    MessageEntity(
                        packetId = "demo_contact_msg_2",
                        conversationId = DEMO_PERSONAL_CONVERSATION_ID,
                        senderNxId = "NX-ANONYMOUS",
                        recipientNxId = DEMO_PERSONAL_PARTICIPANT_NX_ID,
                        plainTextContent = "Great! The local SQLite vault is working cleanly.",
                        status = MessageStatus.DELIVERED.name,
                        timestamp = now - 120_000,
                        isOutgoing = true,
                        transportType = TransportType.INTERNET.name
                    ),
                    MessageEntity(
                        packetId = "demo_contact_msg_3",
                        conversationId = DEMO_PERSONAL_CONVERSATION_ID,
                        senderNxId = DEMO_PERSONAL_PARTICIPANT_NX_ID,
                        recipientNxId = "NX-ANONYMOUS",
                        plainTextContent = "Hey! Welcome to NEXA.",
                        status = MessageStatus.DELIVERED.name,
                        timestamp = now - 60_000,
                        isOutgoing = false,
                        transportType = TransportType.INTERNET.name
                    )
                )

                for (msg in personalMessages) {
                    messageDao.insertMessageIfNotExists(msg)
                }

                // Seed Demo File Transfers for Media Explorer testing
                fileTransferDao?.let { ftDao ->
                    if (ftDao.getTransfersForConversation(DEMO_PERSONAL_CONVERSATION_ID).isEmpty()) {
                        val demoTransfers = listOf(
                            com.nexa.app.data.local.entity.TransferEntity(
                                transferId = 1001L,
                                conversationId = DEMO_PERSONAL_CONVERSATION_ID,
                                fileName = "nature_wallpaper.jpg",
                                fileSizeBytes = 2_450_000L,
                                localUri = "android.resource://com.nexa.app/drawable/ic_nexa_image",
                                fileHash = "hash_demo_img_1",
                                chunksCompleted = 10,
                                totalChunks = 10,
                                status = "COMPLETED",
                                isIncoming = false,
                                timestamp = now - 150_000
                            ),
                            com.nexa.app.data.local.entity.TransferEntity(
                                transferId = 1002L,
                                conversationId = DEMO_PERSONAL_CONVERSATION_ID,
                                fileName = "team_presentation.mp4",
                                fileSizeBytes = 18_200_000L,
                                localUri = "android.resource://com.nexa.app/drawable/ic_nexa_video",
                                fileHash = "hash_demo_vid_1",
                                chunksCompleted = 50,
                                totalChunks = 50,
                                status = "COMPLETED",
                                isIncoming = true,
                                timestamp = now - 130_000
                            ),
                            com.nexa.app.data.local.entity.TransferEntity(
                                transferId = 1003L,
                                conversationId = DEMO_PERSONAL_CONVERSATION_ID,
                                fileName = "NEXA_Protocol_Architecture.pdf",
                                fileSizeBytes = 4_850_000L,
                                localUri = "android.resource://com.nexa.app/drawable/ic_nexa_file",
                                fileHash = "hash_demo_doc_1",
                                chunksCompleted = 20,
                                totalChunks = 20,
                                status = "COMPLETED",
                                isIncoming = false,
                                timestamp = now - 90_000
                            ),
                            com.nexa.app.data.local.entity.TransferEntity(
                                transferId = 1004L,
                                conversationId = DEMO_PERSONAL_CONVERSATION_ID,
                                fileName = "release_vault_backup.zip",
                                fileSizeBytes = 32_100_000L,
                                localUri = "android.resource://com.nexa.app/drawable/ic_nexa_storage",
                                fileHash = "hash_demo_zip_1",
                                chunksCompleted = 80,
                                totalChunks = 80,
                                status = "COMPLETED",
                                isIncoming = true,
                                timestamp = now - 45_000
                            )
                        )
                        for (tr in demoTransfers) {
                            ftDao.insertTransfer(tr)
                        }
                    }
                }
                Log.i(TAG, "Successfully seeded Personal Demo Conversation ($DEMO_PERSONAL_CONVERSATION_ID)")
            }

            // 3. Seed Group Demo Conversation ("Demo Group")
            val existingGroupChat = chatDao.getChatById(DEMO_GROUP_CONVERSATION_ID)
            if (existingGroupChat == null || existingGroupChat.participantName != DEMO_GROUP_PARTICIPANT_NAME) {
                val groupChat = ChatEntity(
                    conversationId = DEMO_GROUP_CONVERSATION_ID,
                    participantNxId = DEMO_GROUP_PARTICIPANT_NX_ID,
                    participantName = DEMO_GROUP_PARTICIPANT_NAME,
                    lastMessageText = "Welcome everyone!",
                    lastMessageTimestamp = now - 30_000,
                    unreadCount = 0
                )
                chatDao.upsertChat(groupChat)

                val groupMessages = listOf(
                    MessageEntity(
                        packetId = "demo_group_msg_1",
                        conversationId = DEMO_GROUP_CONVERSATION_ID,
                        senderNxId = DEMO_MEMBER_SAM_NX_ID,
                        recipientNxId = DEMO_GROUP_PARTICIPANT_NX_ID,
                        plainTextContent = "Testing local offline mesh and store-carry-forward routing.",
                        status = MessageStatus.READ.name,
                        timestamp = now - 150_000,
                        isOutgoing = false,
                        transportType = TransportType.DTN_MESH.name
                    ),
                    MessageEntity(
                        packetId = "demo_group_msg_2",
                        conversationId = DEMO_GROUP_CONVERSATION_ID,
                        senderNxId = DEMO_MEMBER_PRIYA_NX_ID,
                        recipientNxId = DEMO_GROUP_PARTICIPANT_NX_ID,
                        plainTextContent = "All systems verified locally. High-priority privacy active.",
                        status = MessageStatus.READ.name,
                        timestamp = now - 90_000,
                        isOutgoing = false,
                        transportType = TransportType.INTERNET.name
                    ),
                    MessageEntity(
                        packetId = "demo_group_msg_3",
                        conversationId = DEMO_GROUP_CONVERSATION_ID,
                        senderNxId = DEMO_PERSONAL_PARTICIPANT_NX_ID,
                        recipientNxId = DEMO_GROUP_PARTICIPANT_NX_ID,
                        plainTextContent = "Welcome everyone!",
                        status = MessageStatus.DELIVERED.name,
                        timestamp = now - 30_000,
                        isOutgoing = false,
                        transportType = TransportType.INTERNET.name
                    )
                )

                for (msg in groupMessages) {
                    messageDao.insertMessageIfNotExists(msg)
                }
                Log.i(TAG, "Successfully seeded Group Demo Conversation ($DEMO_GROUP_CONVERSATION_ID)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while seeding demo conversations", e)
        }
    }
}
