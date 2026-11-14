# NEXA — Database & User Data Isolation Audit

**Audit Scope:** Complete Relational Schema, Cloud Persistence, Android SQLCipher Room Database, User Data Isolation, IDOR Immunity, and Conversation Integrity  
**Repository:** `H:\chat_app`  
**Date:** November 10, 2026  
**Auditor Mode:** Strict Read-Only Source Code Inspection  

---

## 1. Cloud Database Architecture (PostgreSQL)

The cloud persistence layer is managed through ordered, transactional schema migrations executed via `pgxpool.Pool` in `backend/internal/database/migrations.go`. The schema contains **14 relational tables** designed with strict referential integrity and cascading deletions.

### Relational Schema Inventory

```
+---------------------------------------------------------------------------------------------------------+
|                                    NEXA POSTGRESQL RELATIONAL SCHEMA                                    |
+---------------------------------------------------------------------------------------------------------+
| 1.  accounts                    | id (PK), nx_id (UQ), public_key_hex (UQ), phone_number (UQ), ...      |
| 2.  devices                     | device_id (PK), account_id (FK), nx_id (FK), public_key_hex, is_revoked |
| 3.  sessions                    | token (PK), account_id (FK), nx_id (FK), device_id, expires_at, ...   |
| 4.  phone_verifications         | id (PK), phone_number, nonce (UQ), session_id, verified, expires_at   |
| 5.  connection_requests         | id (PK), sender_nx_id (FK), recipient_nx_id (FK), status, expires_at  |
| 6.  connections                 | id (PK), user_a_nx_id (FK), user_b_nx_id (FK), status, source        |
| 7.  user_blocks                 | blocker_nx_id (FK), blocked_nx_id (FK) [Composite PK]                 |
| 8.  identity_key_bindings       | id (PK), nx_id (FK), device_id (FK), signing_key, dh_key, signature   |
| 9.  signed_prekeys              | id (PK), nx_id (FK), device_id (FK), key_id, public_key_hex, ...     |
| 10. one_time_prekeys            | id (PK), nx_id (FK), device_id (FK), key_id, status, claim_id        |
| 11. prekey_claims               | claim_id (PK), initiator_nx_id (FK), target_nx_id (FK), opk_id, ...   |
| 12. phone_visibility_grants     | id (PK), granter_nx_id (FK), grantee_nx_id (FK), is_active            |
| 13. message_idempotency         | packet_id (PK), sender_nx_id, recipient_nx_id, payload_sha256, exp   |
| 14. schema_migrations           | version (PK), name, applied_at                                         |
+---------------------------------------------------------------------------------------------------------+
```

### Critical Constraints & Integrity Rules
1. **Locked NX-ID Format Check:**
   - Table `accounts`: `CONSTRAINT chk_nx_id_format CHECK (nx_id ~ '^NX-[A-Z0-9]{8}$')` ensures no malformed or non-compliant NX-ID can be persisted.
2. **Canonical Connection Ordering:**
   - Table `connections`: `CONSTRAINT chk_canonical_users CHECK (user_a_nx_id < user_b_nx_id)` and `CONSTRAINT uq_connection_pair UNIQUE (user_a_nx_id, user_b_nx_id)` guarantee that a pair of users has exactly one canonical connection record, preventing duplicate or mirrored states.
3. **Anti-Self Operation Checks:**
   - `connection_requests`: `CONSTRAINT chk_no_self_request CHECK (sender_nx_id != recipient_nx_id)`
   - `user_blocks`: `CONSTRAINT chk_no_self_block CHECK (blocker_nx_id != blocked_nx_id)`
   - `phone_visibility_grants`: `CONSTRAINT chk_different_parties CHECK (granter_nx_id <> grantee_nx_id)`
4. **Prekey Cryptographic Key Format Checks:**
   - Regex constraints ensure all public keys (`identity_key_bindings`, `signed_prekeys`, `one_time_prekeys`) are exactly 64-character hexadecimal strings (`^[0-9a-fA-F]{64}$`).
5. **Prekey Single-Use Status:**
   - `CONSTRAINT chk_opk_status CHECK (status IN ('AVAILABLE', 'CLAIMED', 'CONSUMED', 'BURNED'))` guarantees strict lifecycle progression.

---

## 2. User Data Isolation & Cross-Account Access Analysis

A primary concern in messaging systems is whether an attacker or malicious user can access, read, or tamper with another user's data.

| Resource / Vector | Authorization Mechanism | Database Query Level Enforcement | Result |
| :--- | :--- | :--- | :--- |
| **User Profile GET/PUT** | Session Token (`Authorization: Bearer`) | `WHERE nx_id = $1` where `$1` is `session.NxID` | **Zero IDOR Risk.** The user cannot provide a target ID in the path or query. Data returned is strictly bound to the authenticated session. |
| **Private Messages** | E2EE + Zero-Disk Relay | Messages are **never** stored in PostgreSQL. The relay server operates on ephemeral RAM and 24h Redis buffers with blind tokens. | **Zero Exposure Risk.** Even with complete PostgreSQL database compromise, zero message content is leaked because messages are never stored in the database. |
| **Incoming Connections** | Session Token | `WHERE recipient_nx_id = $1 AND status = 'PENDING'` | User A cannot view User B's incoming invitations. |
| **Device Revocation** | Session Token | Atomic transaction: checks `device.nx_id == session.NxID` before executing revocation. | An attacker cannot revoke devices belonging to another user. |
| **Prekey Bundles** | Session Token (Rate Limited) | Public keys only; private prekeys exist **only** on the user's physical device. | Even if an unauthorized user claims an OPK, they obtain only public ephemeral keys. E2EE Double Ratchet requires knowledge of the private identity key. |
| **Revoked Device Communication**| Hardware Challenge / WS session | `devices.is_revoked = true` causes `GetSession()` to return `false` and WebSocket upgrade to close with `Unauthorized`. | Revoked devices immediately lose access. |

---

## 3. Android Local Database (Room + SQLCipher)

The local client database is defined in `NexaDatabase.kt` and encrypted at rest using SQLCipher with passphrases derived from the Android Keystore master key (`CryptoKeystoreManager.kt`).

### Room Entity & Table Inventory

| Entity Class | Room Table Name | Primary Key | Purpose |
| :--- | :--- | :--- | :--- |
| `UserEntity` | `user_profile` | `nxId` (String) | Stores authenticated local profile (name, phone, avatar, bio, keys). |
| `ChatEntity` | `chat_conversations` | `conversationId` (String) | Conversation summary, participant NX-ID, unread count, last snippet. |
| `MessageEntity` | `messages` | `packetId` (String) | Message records, timestamp, status (`PENDING`, `DELIVERED`, `READ`), text, transport. |
| `ContactEntity` | `contacts` | `nxId` (String) | Contact card, custom nickname, verified status, phone number. |
| `TransferEntity` | `transfers` | `transferId` (Long) | P2P file transfers, filename, size, hash, chunks completed, status. |
| `TransferSessionEntity` | `transfer_sessions` | `sessionId` (String) | Active WebRTC data channel transfer sessions. |
| `MessageDedupEntity` | `message_dedup` | `packetId` (String) | Exact cross-transport deduplication cache (SHA-256 payload digest + seen timestamp). |
| `DtnEnvelopeEntity` | `dtn_envelopes` | `envelopeId` (String) | Store-carry-forward DTN mesh outbox queue with hop limits and TTL. |
| `RatchetStateEntity` | `ratchet_state` | `participantNxId` (String)| Double Ratchet cryptographic session states, root keys, chain keys. |
| `SkippedKeyEntity` | `skipped_keys` | `id` (Auto-inc Long) | Out-of-order message decryption keys retained for delayed packets. |
| `QuarantineEntity` | `quarantine_records`| `fileHash` (String) | Sandbox quarantine records for suspicious incoming files. |
| `DeviceSyncEntity` | `device_sync_records`| `syncId` (String) | Multi-device synchronization state records. |
| `PeerSyncCursorEntity` | `peer_sync_cursors`| `participantNxId` (String)| High-water mark timestamp cursors for peer sync. |

---

## 4. Local Chat Organization & Conversation Integrity

1. **Stable Conversation Identifiers:**
   - Conversation IDs are derived deterministically: `conv_<lower_nxId>_<higher_nxId>` ensuring identical conversation binding between two parties regardless of who initiates.
2. **Outbox Pattern:**
   - Sending a message inserts a record into `messages` with `status = MessageStatus.PENDING` before network dispatch.
   - If network transmission fails, the message remains visible in UI as Pending/Failed with a retry action.
3. **Exact Deduplication Across Transports:**
   - `MessageDedupDao.kt` checks both `packetId` and `payloadSha256` before inserting incoming packets from Internet, SMS, or Nearby Mesh, completely preventing duplicate message bubbles.
4. **Database Reset on Logout / Account Deletion:**
   - Logout calls `userDao.clearProfile()` and `authRepository.clearSession()`.
   - Permanent account deletion invokes `NexaDatabase.clearAllTables()`, deletes SQLCipher database files, and zeroes cryptographic master keys.
5. **Multi-Account Local Separation Note:**
   - NEXA currently operates with a **Single Active Account** model locally (`SELECT * FROM user_profile LIMIT 1`). Multiple concurrent accounts on the same Android installation are not currently supported; switching accounts executes an explicit local database clear and re-sync.
