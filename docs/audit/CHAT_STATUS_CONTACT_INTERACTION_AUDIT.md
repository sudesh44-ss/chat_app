# Chat, Status, and Contact Interaction Audit

## Scope and baseline

This audit records the implementation present before feature changes for the requested chat-list, status-screen, and contact-profile interactions. The worktree already contained uncommitted changes in `MainChatDashboardScreen.kt`, `ChatEntity.kt`, `ChatDao.kt`, `ChatRepositoryImpl.kt`, `NexaDatabase.kt`, `ContactProfileScreen.kt`, and new `StatusRepositoryImpl.kt` / `StatusViewModel.kt`. Those changes were treated as existing work and were not reverted.

## Existing implementation discovered

### Chat list and actions

- `MainChatDashboardScreen` owns the chat list, long-press state, current chat/group tab, and an in-memory selection set. It already calls repository operations for pin, archive, mute, mark-read, delete, and block confirmation. The current context-menu component does not match the requested nine-action order and does not provide Select All, View Status, or the dedicated manual-unread behavior.
- `NexaChatContextMenu` currently offers Pin/Unpin, Archive, View Contact, Mute/Unmute, Select, Delete, Mark as Read/Unread, and Block, in a different order from the requested actions. It uses existing drawable resources and the app's glass surface style.
- `ChatEntity` already has persistent `isPinned`, `pinPosition`, `pinnedAt`, `isArchived`, `archivedAt`, `isMuted`, and `mutedUntil` fields. `ChatDao.getChatsFlow()` excludes archived chats and sorts pinned chats first, then by pin position, recent message timestamp, and conversation ID. Archived chats have a separate flow.
- Existing Room migration 23→24 adds chat organization fields and indexes. The current database version is 26.
- `ChatRepository` exposes pin, archive, mute, archive-list, clear, and delete operations. `ChatRepositoryImpl` performs local persistence, and exposes clear/delete as `Result`s. Clear/delete and pin/archive/mute behaviors should be reused rather than duplicated.
- Chat selection currently resides in the dashboard composable; this is not a domain-level selection model. Eligibility and a Select All action need to be established against the currently displayed list and existing bulk-action behavior.
- There is no separate local manual-unread marker in the inspected `ChatEntity`/DAO API. The current unread count is also used by message/read handling, so repurposing it risks changing message read receipts.

### Blocking and notifications

- `ContactRepository` / `ContactRepositoryImpl` provide existing block/unblock behavior, including local contact status and backend connection enforcement. The profile already has a block dialog. The chat action should call this workflow and must not add another blocking store.
- The notification dispatcher currently gates direct-message notifications using global notification preferences and quiet-hours settings. Its direct-message API accepts a conversation ID but does not itself consult a conversation-scoped mute preference. Incoming notification call sites exist in the repository and transport ingest path, so a per-conversation check must cover all relevant dispatch paths.
- `ConversationPreferences` already stores conversation-scoped mute duration, wallpaper, tone, and vibration preferences. It uses the conversation ID for notification mute state and is already consumed by the contact profile. Status mute must use a separate preference key/store.

### Status storage, expiry, viewer, and visibility

- `StatusUpdateEntity` stores stable status/user IDs, creation and expiration timestamps, privacy, and viewed state. Status rows are persisted in Room and indexed by user and expiration. The existing DAO queries statuses by `expiresAt > currentTime` and supports deletion and expiry purge.
- The inspected `StatusRepositoryImpl` groups eligible statuses by stable user ID, excludes the current user, expired/not-yet-created statuses, and blocked contacts, and supplies active statuses to the dashboard's existing status viewer. It also supports status create/delete/view operations.
- Status expiry is represented as a timestamp (creation plus 24 hours for newly created statuses), but the current flow captures its initial query time and does not independently emit at expiry. A refresh/lifecycle mechanism is needed for statuses expiring while a screen/viewer remains open or the app returns from background.
- No status mute preference or separate Muted Updates grouping was found in the inspected status entity/DAO/repository/ViewModel.
- `NexaSequentialStoryViewer` is the existing viewer and should remain the viewer entry point. Empty-group handling currently dismisses the viewer; callers must avoid opening it for unavailable statuses.
- Privacy enforcement exists in status metadata and the repository's contact/block filtering. The currently inspected active-status query does not filter each status by its privacy value; authorization must be verified and centralized before exposing direct contact-status access.

### Contact profile and navigation

- `ContactProfileScreen` and `ContactProfileViewModel` are the existing profile surface. The ViewModel is initialized with a stable participant NX-ID and conversation ID, reads contact/chat data by those identifiers, and already reflects mute/block profile actions.
- No reactive status-availability state or See Status action was found in the profile screen/ViewModel before feature work. The profile's existing chat action and navigation are wired through the app navigation graph.
- `AuthNavGraph` is the existing route wiring for the dashboard and contact profile. New actions should use these callbacks/routes rather than introduce duplicate destinations.

## Files inspected

- `app/src/main/java/com/nexa/app/presentation/dashboard/MainChatDashboardScreen.kt`
- `app/src/main/java/com/nexa/app/presentation/dashboard/components/NexaChatContextMenu.kt`
- `app/src/main/java/com/nexa/app/presentation/dashboard/components/NexaConversationItem.kt`
- `app/src/main/java/com/nexa/app/presentation/dashboard/components/NexaStoriesRow.kt`
- `app/src/main/java/com/nexa/app/presentation/profile/ContactProfileScreen.kt`
- `app/src/main/java/com/nexa/app/presentation/profile/ContactProfileViewModel.kt`
- `app/src/main/java/com/nexa/app/presentation/status/StatusViewModel.kt`
- `app/src/main/java/com/nexa/app/presentation/status/components/NexaSequentialStoryViewer.kt`
- `app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt`
- `app/src/main/java/com/nexa/app/data/local/entity/ChatEntity.kt`
- `app/src/main/java/com/nexa/app/data/local/dao/ChatDao.kt`
- `app/src/main/java/com/nexa/app/data/local/entity/StatusUpdateEntity.kt`
- `app/src/main/java/com/nexa/app/data/local/dao/StatusUpdateDao.kt`
- `app/src/main/java/com/nexa/app/data/local/dao/ContactDao.kt`
- `app/src/main/java/com/nexa/app/data/local/NexaDatabase.kt`
- `app/src/main/java/com/nexa/app/data/local/preferences/ConversationPreferences.kt`
- `app/src/main/java/com/nexa/app/data/repository/ChatRepositoryImpl.kt`
- `app/src/main/java/com/nexa/app/data/repository/ContactRepositoryImpl.kt`
- `app/src/main/java/com/nexa/app/data/repository/StatusRepositoryImpl.kt`
- `app/src/main/java/com/nexa/app/domain/repository/ChatRepository.kt`
- `app/src/main/java/com/nexa/app/domain/repository/StatusRepository.kt`
- `app/src/main/java/com/nexa/app/core/notification/NexaNotificationDispatcher.kt`

## Database, synchronization, and known gaps at audit time

- Existing chat organization state is local Room state; no corresponding multi-device synchronization contract was found during this audit.
- Status content is also stored locally in Room. Status mute has no existing persistence mechanism.
- Chat pin/archive/mute persistence already exists; additional schema changes should be limited to state not adequately represented by existing fields (notably a local manual-unread marker) and only if a preference-based implementation is not sufficient.
- Message read acknowledgements use a separate message/read path. Manual unread must not mutate message status or emit a read acknowledgement.
- Automated coverage exists for broader chat/status/profile behavior, but the inspected tests do not establish coverage for the requested menu order, manual unread persistence, muted status section, or cross-screen availability behavior.

## Audit conclusion

Reuse the current chat repository, contact blocking workflow, status viewer, profile/navigation destinations, and Room status source. The primary gaps are the chat menu contract/actions, a distinct local manual-unread marker, per-chat notification suppression at all dispatch points, contact-specific status muting and a Muted Updates section, centralized authorization-aware reactive status availability, and profile See Status integration. All implementation and validation claims will be reported separately after changes.
