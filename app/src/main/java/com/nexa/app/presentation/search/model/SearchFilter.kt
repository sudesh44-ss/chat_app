package com.nexa.app.presentation.search.model

import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.MessageType

/**
 * Advanced Search Filters in exact mandatory order:
 * 1. Unread
 * 2. Audio
 * 3. Favorite
 * 4. Archived
 * 5. Groups
 * 6. Contacts
 * 7. Photos
 * 8. Documents
 * 9. Videos
 * 10. GIFs
 * 11. Links
 * 12. Non-contacts
 */
enum class SearchFilter(val displayName: String) {
    UNREAD("Unread"),
    AUDIO("Audio"),
    FAVORITE("Favorites"),
    ARCHIVED("Archived"),
    GROUPS("Groups"),
    CONTACTS("Contacts"),
    PHOTOS("Photos"),
    DOCUMENTS("Documents"),
    VIDEOS("Videos"),
    GIFS("GIFs"),
    LINKS("Links"),
    NON_CONTACTS("Non-contacts")
}

fun SearchFilter.matches(
    conversation: ChatConversation,
    messages: List<Message>,
    knownContactIds: Set<String>
): Boolean = when (this) {
    SearchFilter.UNREAD -> conversation.unreadCount > 0 || conversation.hasManualUnread
    SearchFilter.AUDIO -> messages.any {
        it.messageType == MessageType.AUDIO || it.messageType == MessageType.VOICE
    }
    SearchFilter.FAVORITE -> messages.any { it.isStarred }
    SearchFilter.ARCHIVED -> conversation.isArchived
    SearchFilter.GROUPS -> conversation.isGroup
    SearchFilter.CONTACTS -> !conversation.isGroup && conversation.participantNxId in knownContactIds
    SearchFilter.PHOTOS -> messages.any { it.messageType == MessageType.IMAGE }
    SearchFilter.DOCUMENTS -> messages.any {
        it.messageType == MessageType.DOCUMENT || it.messageType == MessageType.FILE
    }
    SearchFilter.VIDEOS -> messages.any { it.messageType == MessageType.VIDEO }
    SearchFilter.GIFS -> messages.any { it.messageType == MessageType.GIF }
    SearchFilter.LINKS -> messages.any {
        it.plainTextContent.contains("http://", ignoreCase = true) ||
            it.plainTextContent.contains("https://", ignoreCase = true)
    }
    SearchFilter.NON_CONTACTS -> !conversation.isGroup && conversation.participantNxId !in knownContactIds
}
