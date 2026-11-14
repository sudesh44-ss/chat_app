# Chat and Status Availability and Persistence Report

## Database changes

Room is at schema version 27. Migration 26→27 adds:

- `chats.hasManualUnread`, defaulting to false.
- `contacts.isStatusMuted`, defaulting to false.

The migration uses the repository's existing conditional-column helper. Existing chat mute preferences remain separate from status-mute state.

## Manual unread and notification mute

Manual unread is a local chat-list marker; it is not a message receipt or count. The mark-unread operation only sets the marker. The existing mark-read operation clears it while retaining the existing read path.

Conversation notification mute is also checked by the notification dispatcher for direct-message and group notifications using `ConversationPreferences`. Status mute does not suppress chat notifications.

## Status lifetime and privacy

- New text and media statuses receive a 24-hour expiration timestamp.
- The shared active-status flow filters future and expired statuses, then schedules refresh at the next creation/expiration transition.
- Status mute state is persisted on the contact and controls whether that user's group is returned in the regular or **Muted Updates** collection.
- Blocked contacts are excluded. `CONTACTS` visibility is limited to mutual/verified contacts; `PUBLIC` is allowed by the current policy; `EXCLUDED` fails closed because no excluded-recipient list exists in the inspected model.
- The central per-contact viewer flow applies the same authorization checks as the grouped list.

## Related implementation

- `app/src/main/java/com/nexa/app/data/local/NexaDatabase.kt`
- `app/src/main/java/com/nexa/app/data/local/entity/ChatEntity.kt`
- `app/src/main/java/com/nexa/app/data/local/entity/ContactEntity.kt`
- `app/src/main/java/com/nexa/app/data/local/dao/ChatDao.kt`
- `app/src/main/java/com/nexa/app/data/local/dao/ContactDao.kt`
- `app/src/main/java/com/nexa/app/data/repository/StatusRepositoryImpl.kt`
- `app/src/main/java/com/nexa/app/core/notification/NexaNotificationDispatcher.kt`

The migration and runtime behavior have not been exercised on an emulator or physical device in this work; see [CHAT_STATUS_VALIDATION_REPORT.md](./CHAT_STATUS_VALIDATION_REPORT.md).
