package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.domain.model.ConversationBackupPolicy
import com.nexa.app.domain.model.ConversationCustomCategories

@Entity(
    tableName = "chats",
    indices = [
        Index(value = ["isArchived", "isPinned", "lastMessageTimestamp"]),
        Index(value = ["participantNxId"]),
        Index(value = ["chatType"]),
        Index(value = ["isPriorityBackup"]),
        Index(value = ["backupPolicy"])
    ]
)
data class ChatEntity(
    @PrimaryKey
    val conversationId: String,
    val participantNxId: String,
    val participantName: String = "",
    val lastMessageText: String = "",
    val lastMessageTimestamp: Long = 0L,
    val unreadCount: Int = 0,
    val chatType: String = "DIRECT", // DIRECT, GROUP
    val title: String? = null,
    val avatarUrl: String? = null,
    val lastMessageId: String? = null,
    val lastMessageSenderNxId: String? = null,
    val isPinned: Boolean = false,
    val pinPosition: Int = 0,
    val pinnedAt: Long? = null,
    val isArchived: Boolean = false,
    val archivedAt: Long? = null,
    val isMuted: Boolean = false,
    val mutedUntil: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val hasManualUnread: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val isPriorityBackup: Boolean = false,
    @ColumnInfo(defaultValue = "'DEFAULT'")
    val backupPolicy: String = "DEFAULT",
    @ColumnInfo(defaultValue = "NULL")
    val customBackupCategories: String? = null
)

fun ChatEntity.toDomain(): ChatConversation {
    val resolvedChatType = if (chatType == "DIRECT" && (
        conversationId.contains("group", ignoreCase = true) ||
        participantNxId.contains("GROUP", ignoreCase = true) ||
        participantName.contains("group", ignoreCase = true)
    )) "GROUP" else chatType

    val parsedPolicy = try {
        ConversationBackupPolicy.valueOf(backupPolicy.trim().uppercase())
    } catch (e: Exception) {
        if (isPriorityBackup) ConversationBackupPolicy.PRIORITY else ConversationBackupPolicy.DEFAULT
    }
    val parsedCustom = customBackupCategories?.let { ConversationCustomCategories.fromJson(it) }

    return ChatConversation(
        conversationId = conversationId,
        participantNxId = participantNxId,
        participantName = participantName,
        lastMessageText = lastMessageText,
        lastMessageTimestamp = lastMessageTimestamp,
        unreadCount = unreadCount,
        hasManualUnread = hasManualUnread,
        chatType = resolvedChatType,
        title = title,
        avatarUrl = avatarUrl,
        lastMessageId = lastMessageId,
        lastMessageSenderNxId = lastMessageSenderNxId,
        isPinned = isPinned,
        pinPosition = pinPosition,
        pinnedAt = pinnedAt,
        isArchived = isArchived,
        archivedAt = archivedAt,
        isMuted = isMuted,
        mutedUntil = mutedUntil,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isPriorityBackup = isPriorityBackup || parsedPolicy == ConversationBackupPolicy.PRIORITY,
        backupPolicy = parsedPolicy,
        customBackupCategories = parsedCustom
    )
}

fun ChatConversation.toEntity(): ChatEntity {
    val resolvedChatType = if (isGroup) "GROUP" else chatType
    val isPriority = isPriorityBackup || backupPolicy == ConversationBackupPolicy.PRIORITY
    val effectivePolicyStr = if (isPriority && backupPolicy == ConversationBackupPolicy.DEFAULT) {
        ConversationBackupPolicy.PRIORITY.name
    } else {
        backupPolicy.name
    }

    return ChatEntity(
        conversationId = conversationId,
        participantNxId = participantNxId,
        participantName = participantName,
        lastMessageText = lastMessageText,
        lastMessageTimestamp = lastMessageTimestamp,
        unreadCount = unreadCount,
        hasManualUnread = hasManualUnread,
        chatType = resolvedChatType,
        title = title,
        avatarUrl = avatarUrl,
        lastMessageId = lastMessageId,
        lastMessageSenderNxId = lastMessageSenderNxId,
        isPinned = isPinned,
        pinPosition = pinPosition,
        pinnedAt = pinnedAt,
        isArchived = isArchived,
        archivedAt = archivedAt,
        isMuted = isMuted,
        mutedUntil = mutedUntil,
        createdAt = if (createdAt > 0L) createdAt else System.currentTimeMillis(),
        updatedAt = if (updatedAt > 0L) updatedAt else System.currentTimeMillis(),
        isPriorityBackup = isPriority,
        backupPolicy = effectivePolicyStr,
        customBackupCategories = customBackupCategories?.toJson()
    )
}
