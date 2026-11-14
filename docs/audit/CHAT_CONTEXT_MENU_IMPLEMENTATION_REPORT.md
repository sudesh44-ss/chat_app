# Chat Context Menu Implementation Report

## Result

Direct-chat long-press actions are wired through the dashboard using the existing repository and navigation workflows. The menu presents these nine actions in order:

1. View Contact
2. Pin / Unpin
3. Select All
4. Block / Unblock
5. Silent Notifications / Unmute Notifications
6. View Status
7. Mark as Unread
8. Clear Chat
9. Archive

## Behavior and persistence

- Pin, archive, and mute use the existing chat persistence operations.
- Block and unblock use the existing contact-repository workflow.
- Select All operates on the eligible chats currently displayed in the dashboard.
- Clear Chat retains a confirmation step and uses the existing clear operation.
- Mark as Unread sets a persisted local marker. It does not change the unread-message count, message state, or read receipts.
- The header overflow menu is a separate surface; this report covers the dashboard's direct-chat long-press menu.

## Related implementation

- `app/src/main/java/com/nexa/app/presentation/dashboard/components/NexaChatContextMenu.kt`
- `app/src/main/java/com/nexa/app/presentation/dashboard/MainChatDashboardScreen.kt`
- `app/src/main/java/com/nexa/app/presentation/dashboard/components/NexaConversationItem.kt`
- `app/src/main/java/com/nexa/app/domain/repository/ChatRepository.kt`
- `app/src/main/java/com/nexa/app/data/repository/ChatRepositoryImpl.kt`

Persistence and migration details are in [CHAT_STATUS_AVAILABILITY_AND_PERSISTENCE_REPORT.md](./CHAT_STATUS_AVAILABILITY_AND_PERSISTENCE_REPORT.md). Validation limitations are in [CHAT_STATUS_VALIDATION_REPORT.md](./CHAT_STATUS_VALIDATION_REPORT.md).
