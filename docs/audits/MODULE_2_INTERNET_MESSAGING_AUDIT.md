# NEXA — Module 2: Internet Real-Time Messaging Engine
## Comprehensive Architecture Audit, Gap Analysis & Technical Readiness Report

**Date:** 2026-11-14  
**Project:** NEXA Local-First E2EE Privacy Messaging Platform  
**Target Module:** Module 2 — Internet Real-Time Messaging Engine  
**Execution Context:** Strict Internet Messaging Layer (Scope: Android Client, Go WebSocket Relay, Ephemeral Redis Infrastructure, SQLCipher Persistence, E2EE Double Ratchet / Group Sender Keys, FCM Wake-Up, Zero Cloud Plaintext / Zero Permanent Server Chat History)  
**Status:** ARCHITECTURE AUDIT & GAP ANALYSIS COMPLETE (Phase 1)

---

## Executive Summary

This audit evaluates the architecture, design contracts, wire protocol, and current codebase across the Go WebSocket backend (`backend/internal/`), the ephemeral Redis mailbox, the Android local-first client (`app/src/main/java/com/nexa/app/`), and the local Room SQLCipher database against the blueprint defined in `docs/fundamentals/Real-Time Internet Messaging Engine.md` and the Module 2 specifications.

The NEXA messaging platform's core design tenets are:
1. **Zero Server Storage & Zero Plaintext:** The Go relay and Redis act strictly as a "Blind Post Office". Delivered packets are purged instantly upon delivery acknowledgement. No plaintext ever touches the wire or server memory. No chat history is stored in Supabase/PostgreSQL.
2. **Single Multiplexed Pipe:** Exactly one persistent authenticated WebSocket connection per active application session multiplexes all 1-to-1 and group conversations.
3. **Local-First SQLCipher Source of Truth:** The Android client persists decrypted chat history locally in AES-256 SQLCipher; backup is performed zero-knowledge to the user's private Google Drive.
4. **Independent Privacy Controls:** Active Status (Green Dot) and Typing Indicators are strictly separated into two distinct toggles with reciprocity rules enforced on Active Status.

---

## 1. Already Implemented Features

The audit confirms the following components are already implemented and functional in the codebase:

1. **Ed25519 Cryptographic Handshake & Connection State Machine:**
   - Single-use 32-byte cryptographic challenge nonce dispatched by Go relay upon WebSocket connection (`backend/internal/connection/client.go:68-114`).
   - Android client signs challenge using hardware identity key (`WebSocketManager.kt:157-206`).
   - Strict 60-second authentication deadline; unauthorized connections fail closed with code 4001.
2. **Wire Protocol Serialization (Protobuf Binary):**
   - Compact binary Protobuf encoding/decoding defined in `backend/internal/wire/wire.go` and `app/src/main/java/com/nexa/app/core/proto/NexaWirePacket.kt`.
   - Supports `PACKET_MESSAGE`, `PACKET_ACK`, `PACKET_PING`, `PACKET_AUTH_CHALLENGE`, `PACKET_AUTH_RESPONSE`, `PACKET_AUTH_SUCCESS`, `PACKET_AUTH_FAILURE`, `PACKET_TYPING`, `PACKET_DEVICE_SYNC`, `PACKET_SIGNALING`, `PACKET_REGISTER_ROUTING_TOKENS`, `PACKET_PEER_SYNC`, `PACKET_CUSTODY_ACK`.
3. **Double Ratchet E2EE (1-to-1):**
   - Standard Signal-compliant Double Ratchet implementation with X25519 ECDH, HKDF-SHA256, and ChaCha20-Poly1305 AEAD (`DoubleRatchetEngine.kt:1-623`).
   - Fixed-overhead 49-byte encrypted envelope (`EncryptedEnvelope.kt`).
   - Forward secrecy and break-in recovery tested.
4. **Idempotency & Replay Defense (Backend & Android):**
   - Server-side SHA-256 ciphertext digest cache in Redis with 7-day TTL (`hub.go:285-335`). Conflicting payloads with same `PacketId` are rejected; duplicates are suppressed and acknowledged.
   - Client-side unique `packetId` primary key and constraint in Room SQLCipher `messages` table.
5. **Basic Offline Dead-Drop Mailbox & Queue Reconciliation:**
   - Ephemeral Redis queue (`queue/redis_buffer.go`) with `Enqueue`, `ClaimPending`, `Acknowledge`, and `RevertInFlight` operations.
   - Multi-device outgoing message fan-out to sibling devices of authenticated sender (`hub.go:357-370`).
   - Background FCM wake-up ping dispatch (`fcm_service.go`, `hub.go:675-699`).
6. **Local-First SQLCipher Persistence:**
   - Room database with SQLCipher encryption (`NexaDatabase.kt:1-265`, v24).
   - Atomic message insertion, reply metadata (`replyToMessageId`), pinned messages, drafts, and reaction entities.
7. **Single Grey & Double Grey Delivery Tick Foundations:**
   - Server immediately dispatches `ACK_SERVER_RECEIVED` (Single Grey Tick) upon accepting `PACKET_MESSAGE` (`hub.go:337-356`).
   - Recipient dispatches `ACK_DEVICE_DELIVERED` (Double Grey Tick) upon persisting in SQLCipher (`ChatRepositoryImpl.kt:870-884`).
   - Hub reconciles queue and purges buffered packet upon receiving `ACK_DEVICE_DELIVERED` (`hub.go:237-283`).

---

## 2. Partially Implemented Features

1. **Message Status Ticks (Single Grey vs Double Grey vs Double Blue):**
   - While `ACK_SERVER_RECEIVED`, `ACK_DEVICE_DELIVERED`, and `ACK_DEVICE_READ` are defined in `wire.go`, `ChatRepositoryImpl.kt:368-372` currently updates message status to `SENT` immediately when `webSocketManager.sendPacket(wirePacket)` returns true (local socket write). This violates Rule 10: *Do not show a successful delivery tick merely because a WebSocket write succeeded.* Status must transition from `PENDING` to `SENT` only when `ACK_SERVER_RECEIVED` arrives from the server.
   - `markConversationRead()` sends `ACK_DEVICE_READ` only for the latest message in a conversation, rather than tracking or acknowledging all unread message UUIDs.
2. **Typing Indicators:**
   - 1-to-1 typing indicator packets (`PACKET_TYPING`) are transmitted across the WebSocket (`WebSocketManager.kt:284-297`, `hub.go:374-390`).
   - However, the server does not enforce rate limiting on typing packets, allowing potential spamming of `PACKET_TYPING`.
   - Android client does not auto-cancel typing indicator on conversation switch or activity stop.
3. **Offline Queue Flushing:**
   - On reconnect, `flushPendingQueue` pulls up to 50 packets via `ClaimPending`. However, if more than 50 packets are buffered, subsequent batches are not drained automatically until next reconnect or manual cursor pagination.
4. **Connection Permission Verification:**
   - `Hub.connValidator` checks `IsBlocked` and `IsConnected` for 1-to-1 packets. However, this validator is currently in-memory/mock in `main.go`, rather than integrated with the persistent database repository with Redis caching.

---

## 3. Missing Features (Must Be Implemented in Module 2)

1. **Group Messaging Engine & Server Fan-Out:**
   - **Backend:** `Hub` has zero group routing logic. When `recipientNxId` represents a group (e.g. `GRP-XXXX`), the hub attempts to find a client session with that ID, fails, and stores it in offline buffer under `GRP-XXXX`, which no user ever drains.
   - **Group Membership & Cache:** No Redis-cached group member list (`group:members:<group_id> -> Set[nx_id]`).
   - **Group Fan-Out:** No server-side fan-out to online member WebSockets and shared offline storage pointer for offline members.
2. **Group Sender Keys Protocol (E2EE):**
   - No `GroupSenderKeyEngine` on Android. Sending a group message currently requires either individual bilateral Double Ratchet encryption (expensive $O(N)$ CPU/battery) or is not encrypted with sender keys.
   - No group epoch tracking, key rotation on member removal/kick, or forward secrecy for departed members.
3. **Active Status (Green Dot) & Redis Presence Engine:**
   - **Backend:** No presence tracking in Redis (`presence:<nx_id> -> ONLINE/HIDDEN`). Disconnect does not clear presence.
   - **Wire Protocol:** No `PACKET_PRESENCE` in `wire.go` or `NexaWirePacket.kt`.
   - **Android Client:** `ChatViewModel.kt:694` sets `isPeerOnline` purely based on whether the local user is connected to WebSocket and local privacy settings are enabled (`isPeerOnline = showOnlineStatus && presenceMode != OnlinePrivacyMode.NOBODY`). This is hardcoded/simulated state, not real peer presence.
   - **Reciprocity Rule:** Reciprocity enforcement (users who hide their own active status cannot view others') is not enforced via server-side presence filtering.
4. **Message Edit & Delete for Everyone (Encrypted Control Events):**
   - Android only implements local soft-delete (`chatRepository.softDeleteMessage()`).
   - No authenticated control event dispatched over the wire for `EDIT_MESSAGE` or `DELETE_FOR_EVERYONE`.
   - No backend authorization ensuring only the original sender UUID can edit or delete a message.
   - No recipient-side tombstoning of edited/deleted messages.
5. **Sealed Sender & Metadata Privacy:**
   - Envelopes currently expose `senderNxId` in the outer wire header (`NexaWirePacket.senderNxId`).
   - Sealed sender envelope (encrypting the sender identity inside the payload for authorized recipients possessing a delivery token) is not yet supported.

---

## 4. Existing Bugs

1. **Premature `SENT` Status Assignment (Android):**
   - In `ChatRepositoryImpl.kt:368-372`:
     ```kotlin
     isDispatched = webSocketManager.sendPacket(wirePacket)
     if (isDispatched) {
         pendingWirePackets.remove(packetId)
         messageDao.updateMessageStatus(packetId, MessageStatus.SENT.name)
     }
     ```
     This marks the message as `SENT` (Single Grey Tick) the moment bytes are pushed to the OkHttp socket buffer, before the server has received or validated the packet. If the network drops immediately, the user sees a false tick.
2. **Mock Active Status in UI:**
   - In `ChatViewModel.kt:694`:
     ```kotlin
     isPeerOnline = showOnlineStatus && presenceMode != OnlinePrivacyMode.NOBODY
     ```
     Whenever the user opens a chat and is connected to WebSocket, the peer is rendered as online (Green Dot) regardless of whether the peer is actually online, disconnected, or has their presence hidden.
3. **Single-Batch Offline Drain Limit:**
   - `flushPendingQueue` in `hub.go:569` claims a single batch of 50 messages. If a user was offline for 3 days and has 150 pending messages, only the first 50 are delivered upon reconnection; the remaining 100 stay stuck in Redis until subsequent reconnect events.
4. **Group Identifier Misrouting in Relay:**
   - Group messages are treated as individual peer messages. If a message is sent to a group recipient ID, it is dropped or indefinitely buffered under the group ID string in Redis without fan-out.

---

## 5. Architecture Conflicts

1. **P2P Holding vs Blind Dead-Drop Mailbox:**
   - `docs/fundamentals/Real-Time Internet Messaging Engine.md` explicitly resolved the "Two Ships Passing in the Night" dilemma: sender devices MUST NOT hold messages until the receiver comes online. The server MUST accept the encrypted packet into an ephemeral Redis mailbox (7-14 day TTL) with instant purge on ACK. The current code has the buffer infrastructure, but lacks multi-batch draining and group shared-payload pointers.
2. **PostgreSQL vs Redis Separation:**
   - PostgreSQL (Supabase) must NEVER store chat history, messages, or presence.
   - Supabase is solely for Address Book (accepted connections) and group membership tables.
   - Redis RAM is the real-time switchboard, presence store, and ephemeral dead-drop.

---

## 6. Security Vulnerabilities

1. **Missing Sender Authorization on Message Edit/Delete:**
   - Because Edit and Delete for Everyone are not implemented as authenticated wire control packets with sender UUID verification, malicious clients could forge deletions if a loose implementation is added without Ed25519 or session-key validation.
2. **Lack of Rate Limiting on Ephemeral Packets:**
   - While `PACKET_MESSAGE` has Redis idempotency and rate limiting, `PACKET_TYPING` and `PACKET_PING` currently bypass rate limiting in `hub.go`. A compromised client could flood the relay and recipient devices with typing signals.
3. **Group Key Forward Secrecy on Kick/Removal:**
   - Without Group Sender Keys epoch rotation, a removed group member could potentially decrypt future messages if they retained the old symmetric key.

---

## 7. Performance Bottlenecks

1. **$O(N)$ Encrypted Packets for Groups (Current State):**
   - Without Sender Keys, sending a message to a 400-member group would require encrypting 400 separate Double Ratchet ciphertexts on the phone and uploading 400 packets over mobile data, crushing battery and bandwidth.
   - With Sender Keys, the phone encrypts 1 ciphertext; Go server fans out across 400 WebSocket/mailbox pointers in RAM ($O(1)$ client work, sub-millisecond Go fan-out).
2. **Redis Memory Overhead on Large Group Offline Bursts:**
   - If 150 members of a 400-member group are offline, storing 150 full duplicates of the ciphertext wastes Redis memory. A shared ciphertext payload with per-recipient pending references reduces RAM usage by 99%.

---

## 8. Missing Infrastructure

1. **Redis Presence Schema:**
   - `presence:<nx_id> -> "ONLINE" | "HIDDEN"` (String with 45s TTL, refreshed by heartbeat).
   - `presence:subs:<nx_id> -> Set[subscriber_nx_id]` (Tracking who is viewing this conversation).
2. **Redis Group Cache:**
   - `group:members:<group_id> -> Set[nx_id]` (Cached from DB, invalidated on member add/remove).
   - `group:epoch:<group_id> -> int` (Current sender key epoch).
3. **Group Shared Payload Mailbox:**
   - `group:msg:<packet_id> -> { payload, remaining_ack_count, expires_at }`.
   - `pending:<nx_id> -> List[packet_id]`.

---

## 9. Required Migrations

1. **Backend Database Migrations:**
   - Add `groups` and `group_members` tables to PostgreSQL schema:
     - `groups(group_id, name, created_by, epoch, created_at, updated_at)`.
     - `group_members(group_id, nx_id, role, joined_at, left_at, status)`.
2. **Android SQLCipher Room Schema (v25 Migration):**
   - Enhance `messages` table or add indices for fast search, edited message tracking (`editedAt`), and tombstoned deletions (`isDeletedForEveryone`).
   - Add `group_sender_keys` table for storing group ratchet state:
     - `group_id`, `sender_nx_id`, `epoch`, `chain_key_hex`, `iteration`, `updated_at`.

---

## 10. Implementation Dependencies & Roadmap

```
[Phase 1: Architecture Audit] (Completed)
             │
             ▼
[Phase 2: WebSocket Connection & Multi-Device Session Hardening]
             │
             ▼
[Phase 3: 1-to-1 Real-Time Messaging & Server Acceptance ACK (Single Grey Tick)]
             │
             ▼
[Phase 4: Offline Mailbox (Redis Dead-Drop), FCM Wake-Up & Full Queue Draining]
             │
             ▼
[Phase 5: Delivery ACK (Double Grey), Read Receipts (Double Blue) & Ordering]
             │
             ▼
[Phase 6: Group Messaging Engine, Membership Cache & Shared-Payload Fan-Out]
             │
             ▼
[Phase 7: E2EE Group Sender Keys Protocol & Epoch Rotation on Kick]
             │
             ▼
[Phase 8: Encrypted Message Edit & Delete for Everyone Control Events]
             │
             ▼
[Phase 9: Active Status (Green Dot), Redis Presence Engine & Reciprocity Rule]
             │
             ▼
[Phase 10: Independent Typing Indicator (Zero DB, Rate Limited, 3s Timeout)]
             │
             ▼
[Phase 11: Sealed Sender & Metadata Privacy Validation]
             │
             ▼
[Phase 12: Redis Horizontal Scaling & Multi-Relay Routing Verification]
             │
             ▼
[Phase 13: Android Integration & Verification]
             │
             ▼
[Phase 14: Comprehensive Verification, Security & Production Readiness Report]
```

---

## Conclusion

The core cryptographic primitives (Double Ratchet, Ed25519) and basic single-socket relay infrastructure are in place. However, critical gaps exist in **Group Fan-Out and Sender Keys**, **Real Presence/Active Status**, **Message Edit/Delete Control Events**, and **Strict 3-Tier Tick State Transitions**.

With Phase 1 complete, we proceed immediately to **Phase 2 through Phase 14** implementation.
