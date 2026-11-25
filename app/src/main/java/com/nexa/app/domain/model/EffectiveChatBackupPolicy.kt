package com.nexa.app.domain.model

import com.nexa.app.core.backup.BackupCategoryConfig

/**
 * Deterministically resolved effective backup policy for an individual conversation,
 * evaluated against the global [BackupCategoryConfig] and current backup trigger context (manual vs scheduled).
 *
 * Precedence Order:
 * 1. Global category disabled -> data is excluded.
 * 2. Conversation excluded -> conversation data is excluded.
 * 3. Manual-only policy -> include only during eligible manual backup.
 * 4. Custom policy -> apply individual category restrictions.
 * 5. Priority policy -> process earlier than normal conversations.
 * 6. Default policy -> follow global backup settings.
 */
data class EffectiveChatBackupPolicy(
    val conversationId: String,
    val isIncluded: Boolean,
    val isPriority: Boolean,
    val includeMessages: Boolean,
    val includeImages: Boolean,
    val includeVideos: Boolean,
    val includeDocuments: Boolean,
    val includeAudio: Boolean,
    val includeOtherFiles: Boolean
) {
    companion object {
        fun resolve(
            conversationId: String,
            policy: ConversationBackupPolicy,
            customCategories: ConversationCustomCategories?,
            isPriorityFlag: Boolean,
            globalConfig: BackupCategoryConfig,
            isManualBackup: Boolean
        ): EffectiveChatBackupPolicy {
            // Rule 1: Global category chats disabled -> All chats excluded
            if (!globalConfig.includeChats) {
                return EffectiveChatBackupPolicy(
                    conversationId = conversationId,
                    isIncluded = false,
                    isPriority = false,
                    includeMessages = false,
                    includeImages = false,
                    includeVideos = false,
                    includeDocuments = false,
                    includeAudio = false,
                    includeOtherFiles = false
                )
            }

            // Rule 2: Conversation explicitly EXCLUDED
            if (policy == ConversationBackupPolicy.EXCLUDED) {
                return EffectiveChatBackupPolicy(
                    conversationId = conversationId,
                    isIncluded = false,
                    isPriority = false,
                    includeMessages = false,
                    includeImages = false,
                    includeVideos = false,
                    includeDocuments = false,
                    includeAudio = false,
                    includeOtherFiles = false
                )
            }

            // Rule 3: MANUAL_ONLY policy and not currently a manual backup
            if (policy == ConversationBackupPolicy.MANUAL_ONLY && !isManualBackup) {
                return EffectiveChatBackupPolicy(
                    conversationId = conversationId,
                    isIncluded = false,
                    isPriority = false,
                    includeMessages = false,
                    includeImages = false,
                    includeVideos = false,
                    includeDocuments = false,
                    includeAudio = false,
                    includeOtherFiles = false
                )
            }

            // Rule 4: CUSTOM policy with granular overrides
            if (policy == ConversationBackupPolicy.CUSTOM) {
                val custom = customCategories ?: ConversationCustomCategories()
                // Global exclusions strictly constrain custom choices
                val messages = globalConfig.includeChats && custom.includeMessages
                val images = globalConfig.includeImages && custom.includeImages
                val videos = globalConfig.includeVideos && custom.includeVideos
                val documents = globalConfig.includeDocuments && custom.includeDocuments
                val audio = globalConfig.includeAudio && custom.includeAudio
                val otherFiles = globalConfig.includeOtherFiles && custom.includeOtherFiles

                val hasAnyIncluded = messages || images || videos || documents || audio || otherFiles

                return EffectiveChatBackupPolicy(
                    conversationId = conversationId,
                    isIncluded = hasAnyIncluded,
                    isPriority = isPriorityFlag,
                    includeMessages = messages,
                    includeImages = images,
                    includeVideos = videos,
                    includeDocuments = documents,
                    includeAudio = audio,
                    includeOtherFiles = otherFiles
                )
            }

            // Rule 5: PRIORITY policy or isPriorityFlag set
            val isPriority = policy == ConversationBackupPolicy.PRIORITY || isPriorityFlag

            // Rule 6: DEFAULT policy (or PRIORITY with standard category mapping)
            return EffectiveChatBackupPolicy(
                conversationId = conversationId,
                isIncluded = true,
                isPriority = isPriority,
                includeMessages = globalConfig.includeChats,
                includeImages = globalConfig.includeImages,
                includeVideos = globalConfig.includeVideos,
                includeDocuments = globalConfig.includeDocuments,
                includeAudio = globalConfig.includeAudio,
                includeOtherFiles = globalConfig.includeOtherFiles
            )
        }
    }
}
