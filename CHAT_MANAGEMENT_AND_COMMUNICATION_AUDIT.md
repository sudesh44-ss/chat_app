# CHAT MANAGEMENT AND COMMUNICATION AUDIT

## 1. Product identity and architecture

- Product name: NEXA
- Android application package: `com.nexa.app`
- Primary stack: Kotlin + Jetpack Compose + Room persistence + Kotlin coroutines + Flow
- Communication architecture: encrypted message transport over WebSocket/Internet, Optional Data SMS fallback, and DTN mesh routing via the existing transport abstractions
- Local persistence: `app/src/main/java/com/nexa/app/data/local` with Room DAOs, entities, and repository layers; main chat state is stored in `chats`, `messages`, and related local entities
- Navigation: Compose Navigation graph in `presentation/navigation/AuthNavGraph.kt`
- Repository layer: `ChatRepository` and `ChatRepositoryImpl` manage chat state, message persistence, archive/mute state, and message searches

## 2. What already works

- Conversation list and chat list filtering are present in `MainChatDashboardScreen.kt` and `ChatDao.kt`
- Archive metadata exists on `ChatEntity` (`isArchived`, `archivedAt`) and is persisted in the Room schema
- Chat repository methods already support archive and unarchive through `archiveConversation(..)` and `getArchivedConversations()`
- Local message clearing and message search are available through `clearChatMessages` and `searchMessagesInConversation`
- Conversation-level state such as pinning, mute status, unread counters, and timestamps is handled in the DAO and repository layer
- The chat header has an overflow menu and search flow in `ChatScreen.kt`
- Settings and navigation architecture already exist for a reusable settings flow

## 3. Partially implemented areas

- Delete-vs-archive semantics: the app had a mismatch where delete flows were accidentally routing through archive actions in the dashboard and chat UI. This has been corrected to use dedicated delete logic that removes local chat rows and local messages.
- Archive management UI: the database layer supports archive/unarchive, but the settings surface was missing a proper `Settings -> Archive` page and a user-accessible archive list.
- Chat overflow menu: the conversation menu still needed to align with the final required action list (View Profile, Search, Delete Chat, Clear Chat, Shared Files, Pinned Messages, Mute Notifications, Archive).
- Selection-mode actions: the chat selection flow needed to be consistent with archive/delete semantics, without duplicating unrelated actions unnecessarily.

## 4. Missing or incomplete functionality

- Settings archive listing and unarchive action were absent from the application UI.
- There was no direct archive screen under Settings for managing archived conversations.
- Delete chat confirmation and local deletion policies needed to be explicitly enforced in the dashboard and chat header flows.
- Clear chat confirmation needed to remain distinct from delete chat semantics.
- A production-grade audit report for the chat-management flow was not present at the repo root.

## 5. Existing components that can be reused

- `ChatDao.kt`: chat query logic, archive filtering, unread handling, and persistence hooks
- `ChatEntity.kt`: canonical local persistence object for conversation state and archive metadata
- `ChatRepositoryImpl.kt`: central service to archive, mute, search, clear, and manage chat state
- `MainChatDashboardScreen.kt`: dashboard list, long-press UX, multi-selection orchestration, and confirmation dialogs
- `NexaChatOverflowMenu.kt`: header action menu rendering
- `SettingsScreen.kt` and `SettingsDetailPage` components: consistent settings screen and detail UX
- Existing `MessageDao` deletion APIs: `deleteMessagesForConversation` for removing local conversation history

## 6. Architectural conflicts and risks

- The app has a more complete local data model than the UI currently exposes. This is beneficial, but it means the UI can drift from repository capability if flows are not kept aligned.
- Archive and delete are conceptually distinct states; conflating them in UI or data flow can permanently hide conversations or fail retention expectations.
- Chat deletion should remove both the chat record and the associated local messages; otherwise the app can leave stale message data behind in the local database.
- Long-press and selection actions should not duplicate items or silently trigger wrong lifecycle actions.
- Multi-device sync concerns should be handled through the existing repository and DAO model, but the current app still depends on local-first behavior and should never assume remote deletion for a user-initiated local delete.

## 7. Required database and API changes

- No fundamental schema rewrite was required; the archive fields were already present in `ChatEntity` and DAO queries.
- A dedicated `deleteChat` repository contract was added so the UI can call the correct deletion flow instead of reusing archive semantics.
- The repository implementation now ensures `deleteChat` removes local message history and the chat record together.
- The archive screen uses the existing `getArchivedConversations()` repository flow and `archiveConversation(conversationId, false)` to restore archived conversations.

## 8. Implementation dependencies and risks

- The archive and delete features depend on the Room-based repository contract and the existing Compose navigation stack.
- Chat UI actions must remain consistent with local persistence and not assume a server-controlled deletion state.
- Offline and app-restart scenarios rely on the persisted `isArchived` flag and the underlying Room database state.
- On app restarts, archived conversations must remain hidden from the main list because the DAO query filters `WHERE isArchived = 0` for the active list.

## 9. Summary

The codebase already had the correct database-level primitives for conversation archiving, local deletion, and chat state management. The primary gap was UI and flow consistency: archive and delete were not always separated correctly, and the settings flow lacked a dedicated archive screen. The final implementation resolves those mismatches without duplicating unrelated chat-management actions.
