# NEXA — Reply-to-Message Feature: Implementation Impact Report

**Date:** 2026-11-10  
**Author:** NEXA Engineering  
**Scope:** Android Core, Jetpack Compose UI, Room Database, Double Ratchet E2EE Transport, Multi-Transport Ingest, Group Chat

---

## 1. Existing System Audit & Gap Analysis

| Component | Current State | Target State | Gap / Impact |
|---|---|---|---|
| **Room Schema (`messages`)** | `packetId`, `conversationId`, `senderNxId`, `recipientNxId`, `plainTextContent`, `status`, `timestamp`, `isOutgoing`, `transportType`, `isPinned`, `pinnedExpiresAt`, `pinnedAt` (Version 20) | Add `replyToMessageId TEXT DEFAULT NULL`, `replyToSenderNxId TEXT DEFAULT NULL`, `replyToSnippet TEXT DEFAULT NULL`, with index on `replyToMessageId` (Version 21) | Schema migration required; bump version from 20 to 21 with non-destructive `MIGRATION_20_21`. |
| **Domain Model (`Message`)** | Contains only base fields and pin metadata | Add `replyToMessageId: String? = null`, `replyToSenderNxId: String? = null`, `replyToSnippet: String? = null` | Update `toDomain()` and `toEntity()` mappers across data and domain layers. |
| **Composer UI (`NexaMessageComposer`)** | Minimal stub banner with hardcoded "Replying to yourself" / "Replying to message"; not wired to actual sender resolution or attachment types | Full Liquid Glass / AMOLED preview banner displaying original sender name, text or attachment preview (image, video, audio, document), '✕' cancel button, smooth expand/collapse animation | Rewire composer state, enhance reply preview banner to handle attachments and contacts. |
| **Message Bubble UI (`NexaMessageBubble`)** | Renders only text, timestamp, delivery indicator, pin badge, reactions | Quoted preview block inside bubble (both incoming & outgoing), clickable tap-to-original navigation, distinct visual accent bar | Integrate quoted message card inside bubble with fallback for deleted/unavailable originals. |
| **Gestures** | Only tap and long-click | Swipe-to-reply: Right swipe on incoming message, Left swipe on outgoing message, threshold detection (~60dp), reply icon reveal animation, haptic feedback, spring reset | Implement `SwipeToReplyContainer` composable wrapper with horizontal drag detection. |
| **Context Menu** | `ContextMenuAction.REPLY` defined in menu, but in `ChatScreen` it only set a local ephemeral state and was wiped on send | Unified reply selection: sets `replyingMessage` in ViewModel/UI, pre-populates composer preview, passes `replyToMessageId` on dispatch | Connect context menu action to shared reply handler. |
| **Navigation & Highlight** | None for replies (only pinned banner navigation existed) | Tapping quoted preview animates scroll to original message in `LazyColumn`, triggers temporary highlight pulse (~1500ms auto-dismiss) | Implement scroll-to-index and transient highlight state in `ChatScreen`. |
| **E2EE Transport** | Raw UTF-8 bytes encrypted via Double Ratchet ChaCha20-Poly1305 | If reply, encode JSON payload `{"type":"CHAT_MESSAGE","text":...,"replyToMessageId":...,"replyToSender":...,"replyToSnippet":...}` inside Double Ratchet ciphertext; decrypt and parse on receiver | Server remains zero-disk ephemeral relay; reply metadata is end-to-end encrypted; backward compatible with plain string payloads. |
| **Multi-Transport Ingest** | `NexaUniversalTransportIngest` extracts plain string from decrypted bytes | If payload is JSON `CHAT_MESSAGE`, unpacks `text`, `replyToMessageId`, `replyToSender`, `replyToSnippet` and persists in Room | Ingest pipeline updated for Internet WebSocket, Cellular Data SMS, and Offline DTN Mesh. |
| **Deleted Originals** | Unhandled | If original is deleted or unavailable, render safe fallback snapshot or "Original message was deleted" banner; no cascade deletion | Robust local resolution with fallback snippet. |
| **Group Chats** | Single 1-on-1 recipient mapping in composer | Resolve `senderNxId` in group context to distinct participant display names | Group sender resolution supported in quote bubbles and composer. |

---

## 2. Architectural Impact & Security Verification

1. **Zero Server Knowledge:** The Go backend relays opaque Protobuf `EncryptedMessagePayload` packets. Reply metadata (`replyToMessageId`, snippet) is encrypted inside the AEAD ciphertext, ensuring the server learns nothing about reply threads or quotes.
2. **Local Vault Integrity:** Room migration preserves 100% of existing message data via SQLite `ALTER TABLE ADD COLUMN` with null defaults. Backups created via `VACUUM INTO` preserve reply references across backup and restore.
3. **No Cascade Deletion:** Deleting an original message does not delete child replies. The quote preview displays a graceful fallback ("Original message was deleted").
4. **Offline Resilience:** Replies work across Internet WebSocket, Cellular Binary Data SMS Port 8901, and Delay-Tolerant Mesh (BLE DTN).

---

## 3. Implementation Plan

- **Step 1:** Update `MessageEntity.kt`, `Message.kt`, `NexaDatabase.kt` (v21 + `MIGRATION_20_21`), and `MessageDao.kt`.
- **Step 2:** Update `ChatRepository.kt`, `ChatRepositoryImpl.kt`, and `NexaUniversalTransportIngest.kt` for encrypted reply transport and ingest.
- **Step 3:** Implement `SwipeToReplyContainer.kt` with haptic feedback and swipe-to-reply animation.
- **Step 4:** Enhance `NexaMessageComposer.kt` with rich attachment/contact reply preview.
- **Step 5:** Enhance `NexaMessageBubble.kt` with clickable quoted message bubble and fallback states.
- **Step 6:** Wire tap-to-original navigation, scrolling, and transient highlight in `ChatScreen.kt` and `ChatViewModel.kt`.
- **Step 7:** Author unit test suite `ChatReplyUnitTests.kt` and execute full regression test pass.
- **Step 8:** Update documentation and provide completion report.
